package com.khanhvu.livingvillages.wall;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildSite;
import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.road.Demolisher;
import com.khanhvu.livingvillages.road.RoadSegment;
import com.khanhvu.livingvillages.road.VillageRoads;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where buildings go once a village has a city wall (spec v3-GĐ 3). Houses and workshops fill the inside: a scan
 * over the whole area inside the wall, spread over several ticks, tries every spot on a 2-block grid, closest to a
 * road first, keeping the square around the bell, the roads and a way in from every gate free. When not even the
 * smallest house or workshop fits, the city is full. Farms and pens go outside, in a belt along the wall, near the
 * gates first.
 */
public final class CitySites {
	private static final int COLUMNS_PER_TICK = 4000;
	private static final int QUICK_CHECKS_PER_TICK = 120;
	private static final int SITE_TRIES_PER_TICK = 6;
	/** Farm and pen attempts start this close to a gate. */
	private static final int NEAR_GATE = 16;
	/** Branch positions tried before giving up, so one blocked line does not stop the city growing. */
	private static final int BRANCH_POSITIONS_PER_TRY = 8;
	/** Farms and pens keep this far from the wall. */
	private static final int WALL_CLEARANCE = 2;

	private enum Phase {
		SURVEY,
		PLAN,
		SEARCH
	}

	/** One search for a site inside the walls; runtime only (a restart simply starts the next search). */
	private static final class Scan {
		final int ringId;
		final BuildingTemplate requested;
		/** Templates to try in order: the requested one, then smaller ones, then the smallest house or workshop. */
		final List<BuildingTemplate> templates = new ArrayList<>();
		@Nullable
		final BuildingTemplate smallestHome;
		boolean smallestHomeFailed;
		Phase phase = Phase.SURVEY;
		final int minX;
		final int minZ;
		final int width;
		final int depth;
		final boolean[] inside;
		final boolean[] road;
		boolean[] reserved;
		int cursor;
		/** Places a building may stand beside a road: the road column it fronts on, and which way its front must look. */
		int[] slotX = new int[0];
		int[] slotZ = new int[0];
		Direction[] slotFacing = new Direction[0];
		int templateIndex;
		int slotIndex;
		/** A branch was opened because nothing fitted, so the slots are worked out again before giving up. */
		boolean branchOpened;

		Scan(WallRing ring, BuildingTemplate requested, @Nullable BuildingTemplate smallestHome) {
			this.ringId = ring.getId();
			this.requested = requested;
			this.smallestHome = smallestHome;
			int[] polygon = ring.getPolygon();
			int x0 = Integer.MAX_VALUE;
			int z0 = Integer.MAX_VALUE;
			int x1 = Integer.MIN_VALUE;
			int z1 = Integer.MIN_VALUE;
			for (int i = 0; i < polygon.length; i += 2) {
				x0 = Math.min(x0, polygon[i]);
				x1 = Math.max(x1, polygon[i]);
				z0 = Math.min(z0, polygon[i + 1]);
				z1 = Math.max(z1, polygon[i + 1]);
			}
			this.minX = x0;
			this.minZ = z0;
			this.width = x1 - x0 + 1;
			this.depth = z1 - z0 + 1;
			this.inside = new boolean[width * depth];
			this.road = new boolean[width * depth];
		}

		int index(int x, int z) {
			int dx = x - minX;
			int dz = z - minZ;
			return dx < 0 || dz < 0 || dx >= width || dz >= depth ? -1 : dx * depth + dz;
		}
	}

	private static final Map<UUID, Scan> SCANS = new HashMap<>();

	private CitySites() {
	}

	/**
	 * Whether buildings follow the planned-city rules: along the roads inside, farms and pens outside. A v4 village
	 * does from the moment it has a frame -- the boundary and the roads are known then, long before any stone goes up.
	 * A village from a v3 world has no frame and keeps the old rule: only once its wall is a city wall.
	 */
	public static boolean active(VillageRecord village) {
		if (!WallBuilder.enabled()) {
			return false;
		}
		if (village.getFrame() != null) {
			return true;
		}
		WallRing outer = village.getWalls().outer();
		return outer != null && outer.getType() == WallRing.Type.CITY;
	}

	/** Houses and workshops are built inside the walls; farms and pens outside. */
	public static boolean insideKind(@Nullable BuildingKind kind) {
		return kind != BuildingKind.FARM && kind != BuildingKind.PEN;
	}

	public static boolean isFull(VillageRecord village) {
		WallRing outer = village.getWalls().outer();
		return active(village) && outer != null && outer.isFull();
	}

	/** The build command looks again even in a full city. */
	public static void resetFull(VillageRecord village) {
		WallRing outer = village.getWalls().outer();
		if (outer != null) {
			outer.setFull(false);
		}
	}

	public static boolean isScanning(VillageRecord village) {
		return SCANS.containsKey(village.getId());
	}

	/** Starts looking for a site inside the walls for {@code requested} (or a smaller building of the same kind). */
	public static void request(ServerLevel level, VillageRecord village, BuildingTemplate requested, List<BuildingTemplate> sameKind) {
		List<BuildingTemplate> all = BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village));
		BuildingTemplate smallestHome = all.stream().filter(b -> insideKind(b.kind())).min(Comparator.comparingInt(CitySites::area)).orElse(null);
		WallRing outer = village.getWalls().outer();
		if (outer == null) {
			return; // the frame is fixed but the ring is not up yet: nothing to scan inside
		}
		Scan scan = new Scan(outer, requested, smallestHome);
		scan.templates.add(requested);
		sameKind.stream().filter(b -> b != requested && area(b) < area(requested)).min(Comparator.comparingInt(CitySites::area))
				.ifPresent(scan.templates::add);
		SCANS.put(village.getId(), scan);
		LivingVillages.debug("Village {}: looking inside the walls for a site for {}", village.getId(), requested.id());
	}

	private static int area(BuildingTemplate building) {
		BoundingBox box = building.contentBox();
		return box.getXSpan() * box.getZSpan();
	}

	/** Advances the village's search, if any. */
	public static void tick(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		Scan scan = SCANS.get(village.getId());
		if (scan == null) {
			return;
		}
		WallRing ring = village.getWalls().outer();
		if (!active(village) || village.getProject() != null || ring == null || ring.getId() != scan.ringId) {
			SCANS.remove(village.getId());
			return;
		}
		switch (scan.phase) {
			case SURVEY -> survey(level, ring, scan);
			case PLAN -> plan(village, ring, scan);
			case SEARCH -> search(level, registry, village, ring, scan);
		}
	}

	/** Which columns are inside the wall and which are road, a few thousand columns per tick. */
	private static void survey(ServerLevel level, WallRing ring, Scan scan) {
		Set<Block> roads = RoadBuilder.roadBlocks();
		int total = scan.width * scan.depth;
		int end = Math.min(total, scan.cursor + COLUMNS_PER_TICK);
		for (int i = scan.cursor; i < end; i++) {
			int x = scan.minX + i / scan.depth;
			int z = scan.minZ + i % scan.depth;
			if (!ring.contains(x + 0.5, z + 0.5) || !level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			scan.inside[i] = true;
			scan.road[i] = roads.contains(level.getBlockState(new BlockPos(x, SiteFinder.groundTop(level, x, z), z)).getBlock());
		}
		scan.cursor = end;
		if (end >= total) {
			scan.phase = Phase.PLAN;
		}
	}

	/**
	 * Space kept free (the square around the bell, roads and one block beside them, a way in from each gate to the
	 * nearest road or the bell), then the grid of spots ordered by their distance to a road over the inside.
	 */
	private static void plan(VillageRecord village, WallRing ring, Scan scan) {
		LVConfig config = LVConfig.get();
		int total = scan.width * scan.depth;
		boolean[] reserved = new boolean[total];
		BlockPos bell = village.getBellPos();
		int plaza = config.plazaRadius;
		for (int dx = -plaza; dx <= plaza; dx++) {
			for (int dz = -plaza; dz <= plaza; dz++) {
				int i = scan.index(bell.getX() + dx, bell.getZ() + dz);
				if (i >= 0 && dx * dx + dz * dz <= plaza * plaza) {
					reserved[i] = true;
				}
			}
		}
		for (int i = 0; i < total; i++) {
			if (!scan.road[i]) {
				continue;
			}
			int x = scan.minX + i / scan.depth;
			int z = scan.minZ + i % scan.depth;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int j = scan.index(x + dx, z + dz);
					if (j >= 0) {
						reserved[j] = true;
					}
				}
			}
		}
		for (int[] gate : gateCenters(ring)) {
			reserveGateWay(scan, reserved, gate[0], gate[1], bell, config.gateWidth / 2, plaza);
		}
		// Inner rings (v3-GĐ 4): their walls, towers and the walkway along them stay free.
		int buffer = config.wallInnerBuffer;
		for (WallRing inner : village.getWalls().getRings()) {
			if (inner == ring) {
				continue;
			}
			for (int c = 0; c < inner.size(); c++) {
				reserveSquare(scan, reserved, inner.x(c), inner.z(c), buffer);
			}
			for (WallRing.Tower tower : inner.getTowers()) {
				reserveSquare(scan, reserved, tower.centerX(), tower.centerZ(), 2 + buffer);
			}
		}
		// The planned roads themselves, and a block either side, are the street: nothing is built on them.
		int keep = config.roadWidth / 2 + 1;
		for (RoadSegment segment : village.getRoads().getSegments()) {
			for (int k = 0; k < segment.length(); k++) {
				BlockPos cell = segment.cell(k);
				reserveSquare(scan, reserved, cell.getX(), cell.getZ(), keep);
			}
		}
		scan.reserved = reserved;
		buildSlots(village, scan, config);
		scan.phase = Phase.SEARCH;
	}

	/**
	 * Every place a building may stand beside a road (spec v4 §7): walking each planned road, both sides, a step of
	 * {@code plotSpacing} apart, nearest the middle of the village first so a city fills outwards. A slot records which
	 * way the building's front has to look, which is what puts its door on the street.
	 */
	private static void buildSlots(VillageRecord village, Scan scan, LVConfig config) {
		BlockPos centre = VillageRoads.centre(village);
		BlockPos from = centre != null ? centre : village.getBellPos();
		List<int[]> slots = new ArrayList<>();
		for (RoadSegment segment : village.getRoads().getSegments()) {
			boolean horizontal = segment.horizontal();
			for (int k = 0; k < segment.length(); k += Math.max(1, config.plotSpacing)) {
				BlockPos cell = segment.cell(k);
				if (scan.index(cell.getX(), cell.getZ()) < 0) {
					continue;
				}
				// Perpendicular to the road: one slot on either side, each facing back onto it.
				for (Direction side : horizontal ? new Direction[] {Direction.NORTH, Direction.SOUTH}
						: new Direction[] {Direction.WEST, Direction.EAST}) {
					slots.add(new int[] {cell.getX(), cell.getZ(), side.ordinal(),
							cell.distManhattan(from)});
				}
			}
		}
		slots.sort(Comparator.comparingInt(a -> a[3]));
		scan.slotX = new int[slots.size()];
		scan.slotZ = new int[slots.size()];
		scan.slotFacing = new Direction[slots.size()];
		for (int i = 0; i < slots.size(); i++) {
			int[] slot = slots.get(i);
			scan.slotX[i] = slot[0];
			scan.slotZ[i] = slot[1];
			// The slot sits on the far side of the road from where the building stands, so the front faces the road.
			scan.slotFacing[i] = Direction.values()[slot[2]];
		}
		scan.templateIndex = 0;
		scan.slotIndex = 0;
	}

	private static void reserveSquare(Scan scan, boolean[] reserved, int x, int z, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int i = scan.index(x + dx, z + dz);
				if (i >= 0) {
					reserved[i] = true;
				}
			}
		}
	}

	/** Reserves a strip gateWidth wide from the gate towards the bell, until it meets a road or the square. */
	private static void reserveGateWay(Scan scan, boolean[] reserved, int gx, int gz, BlockPos bell, int half, int plaza) {
		double dx = bell.getX() - gx;
		double dz = bell.getZ() - gz;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1) {
			return;
		}
		for (int step = 0; step <= length; step++) {
			int x = gx + (int) Math.round(dx * step / length);
			int z = gz + (int) Math.round(dz * step / length);
			int centre = scan.index(x, z);
			double toBell = length - step;
			if (step > half + 2 && ((centre >= 0 && scan.road[centre]) || toBell <= plaza)) {
				return; // reached a road or the square
			}
			for (int ox = -half; ox <= half; ox++) {
				for (int oz = -half; oz <= half; oz++) {
					int i = scan.index(x + ox, z + oz);
					if (i >= 0) {
						reserved[i] = true;
					}
				}
			}
		}
	}

	/** Tries spots until one fits, a bounded number per tick; cheap checks first, the full site check last. */
	private static void search(ServerLevel level, VillageRegistry registry, VillageRecord village, WallRing ring, Scan scan) {
		LVConfig config = LVConfig.get();
		int quick = 0;
		int tries = 0;
		while (quick < QUICK_CHECKS_PER_TICK && tries < SITE_TRIES_PER_TICK) {
			if (scan.templateIndex >= scan.templates.size()) {
				// Nothing fits anywhere along the roads: open one more street and look again before calling it full.
				if (!scan.branchOpened) {
					RoadSegment branch = openBranch(level, registry, village);
					if (branch != null) {
						scan.branchOpened = true;
						// The new street was added after the kept space was worked out: keep it free too, or a
						// building would be put on the road that was just opened for it.
						int keep = config.roadWidth / 2 + 1;
						for (int k = 0; k < branch.length(); k++) {
							BlockPos cell = branch.cell(k);
							reserveSquare(scan, scan.reserved, cell.getX(), cell.getZ(), keep);
						}
						// The frontage has changed, so the earlier "nothing fits" verdict no longer stands.
						scan.smallestHomeFailed = false;
						buildSlots(village, scan, config);
						continue;
					}
				}
				finishFailed(level, registry, village, ring, scan);
				return;
			}
			BuildingTemplate building = scan.templates.get(scan.templateIndex);
			if (scan.slotIndex >= scan.slotX.length) {
				// This building fits nowhere; the smallest house or workshop is the test for "full".
				if (building == scan.smallestHome) {
					scan.smallestHomeFailed = true;
				}
				scan.templateIndex++;
				scan.slotIndex = 0;
				if (scan.templateIndex >= scan.templates.size() && !scan.smallestHomeFailed && scan.smallestHome != null
						&& !scan.templates.contains(scan.smallestHome)) {
					scan.templates.add(scan.smallestHome);
				}
				continue;
			}
			int roadX = scan.slotX[scan.slotIndex];
			int roadZ = scan.slotZ[scan.slotIndex];
			Direction facing = scan.slotFacing[scan.slotIndex];
			scan.slotIndex++;
			// A template that does not say where its front is may stand any way round (spec v4 §6 fallback 2).
			Rotation rotation = building.rotationFacing(facing);
			for (Rotation candidate : rotation != null ? new Rotation[] {rotation} : Rotation.values()) {
				quick++;
				BlockPos centre = centreBeside(building, candidate, roadX, roadZ, facing, config);
				BoundingBox footprint = SiteFinder.footprintAt(building, centre.getX(), centre.getZ(), candidate);
				if (!quickFits(level, ring, scan, footprint, config)) {
					continue;
				}
				tries++;
				BuildSite site = SiteFinder.tryAt(level, building, centre.getX(), centre.getZ(), candidate, config.infillMargin,
						SiteFinder.insideFrameCut());
				if (site != null) {
					SCANS.remove(village.getId());
					LivingVillages.debug("Village {}: site on a road for {} at {}, front facing {}", village.getId(), building.id(),
							site.origin(), facing);
					VillageTicker.beginProject(level, registry, village, building, site);
					return;
				}
			}
		}
	}

	/**
	 * Where a building stands to front onto the road column: back from the street by its own half depth, so its front
	 * wall ends up just off the road and {@code facing} points from the building at it.
	 */
	private static BlockPos centreBeside(BuildingTemplate building, Rotation rotation, int roadX, int roadZ, Direction facing, LVConfig config) {
		BoundingBox local = building.worldBox(BlockPos.ZERO, rotation);
		Direction out = facing.getOpposite();
		int depth = out.getAxis() == Direction.Axis.X ? local.getXSpan() : local.getZSpan();
		// The street keeps roadWidth/2 + 1 columns either side free, so the near wall has to start one beyond that.
		int away = config.roadWidth / 2 + 2 + depth / 2;
		return new BlockPos(roadX + out.getStepX() * away, 0, roadZ + out.getStepZ() * away);
	}

	/**
	 * Opens the next branch street (spec v4 §7.4, §8.4). A street may take down one building of the mod's own to get
	 * through -- inside a planned city the road wins over the house, and the village then wants a replacement, which
	 * goes on a street. How many it may take at once grows with the village level: a hamlet cannot spare a house, a
	 * city replanning itself may cut a row. A position costing more than that, or crossing something the mod may not
	 * touch, is passed over for the next one. Null when the frame has no room for another street, which is what makes
	 * the city full.
	 */
	@Nullable
	private static RoadSegment openBranch(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		for (int attempt = 0; attempt < BRANCH_POSITIONS_PER_TRY; attempt++) {
			RoadSegment branch = VillageRoads.nextBranch(village);
			if (branch == null) {
				return null;
			}
			List<BoundingBox> blocking = new ArrayList<>();
			for (BoundingBox plot : village.getPlots()) {
				if (crosses(branch, plot)) {
					blocking.add(plot);
				}
			}
			int allowed = demolishAllowance(village);
			boolean mayClear = blocking.size() <= allowed && blocking.stream().allMatch(p -> Demolisher.canDemolish(level, village, p));
			if (!mayClear) {
				LivingVillages.debug("Village {}: branch position skipped, {} building(s) in the way, {} allowed at this level",
						village.getId(), blocking.size(), allowed);
				continue;
			}
			boolean cleared = true;
			for (BoundingBox plot : blocking) {
				cleared &= Demolisher.demolish(level, registry, village, plot);
			}
			if (!cleared) {
				continue;
			}
			village.getRoads().add(branch);
			registry.setDirty();
			LivingVillages.debug("Village {}: branch street opened ({},{})->({},{})", village.getId(),
					branch.x1(), branch.z1(), branch.x2(), branch.z2());
			return branch;
		}
		return null;
	}

	/** Buildings of its own one street may take down, by village level (spec v4 §8.4). */
	private static int demolishAllowance(VillageRecord village) {
		List<Integer> byLevel = LVConfig.get().demolishPerBranchByLevel;
		return byLevel.get(Math.clamp(village.getLevel(), 0, byLevel.size() - 1));
	}

	private static boolean crosses(RoadSegment segment, BoundingBox plot) {
		int keep = LVConfig.get().roadWidth / 2 + 1;
		return plot.intersects(Math.min(segment.x1(), segment.x2()) - keep, Math.min(segment.z1(), segment.z2()) - keep,
				Math.max(segment.x1(), segment.x2()) + keep, Math.max(segment.z1(), segment.z2()) + keep);
	}

	/**
	 * Checks that need (almost) no world access: inside the wall with room for the walkway along it, clear of kept
	 * space and plots, and natural ground under the middle.
	 */
	private static boolean quickFits(ServerLevel level, WallRing ring, Scan scan, BoundingBox footprint, LVConfig config) {
		BoundingBox walkway = footprint.inflatedBy(config.wallInnerBuffer);
		if (!VillageBoundary.containsBox(ring.getPolygon(), walkway)) {
			return false;
		}
		for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
				int i = scan.index(x, z);
				if (i < 0 || !scan.inside[i] || scan.reserved[i]) {
					return false;
				}
			}
		}
		BoundingBox spaced = footprint.inflatedBy(config.infillMargin);
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot.intersects(spaced.minX(), spaced.minZ(), spaced.maxX(), spaced.maxZ())) {
					return false;
				}
			}
			BuildProject project = other.getProject();
			if (project != null && project.getFootprint().intersects(spaced.minX(), spaced.minZ(), spaced.maxX(), spaced.maxZ())) {
				return false;
			}
		}
		BlockPos centre = footprint.getCenter();
		return SiteFinder.isNaturalGround(level.getBlockState(new BlockPos(centre.getX(), SiteFinder.groundTop(level, centre.getX(), centre.getZ()), centre.getZ())));
	}

	/** Nothing fits: when not even the smallest house or workshop did, the city is full (spec v3-GĐ 3). */
	private static void finishFailed(ServerLevel level, VillageRegistry registry, VillageRecord village, WallRing ring, Scan scan) {
		SCANS.remove(village.getId());
		if (scan.smallestHomeFailed) {
			if (ring.noteFull()) {
				Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.city_full");
			}
			LivingVillages.debug("Village {}: no room left inside the walls", village.getId());
		}
		VillageTicker.siteFailed(level, registry, village, scan.requested);
	}

	/** For a road from a building outside the city wall: the middle of the nearest gate; null inside or without a city wall. */
	@Nullable
	public static BlockPos gateFor(VillageRecord village, BlockPos start) {
		WallRing ring = village.getWalls().outer();
		if (!active(village) || ring == null) {
			return null;
		}
		if (ring.contains(start.getX() + 0.5, start.getZ() + 0.5)) {
			return null;
		}
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int[] gate : gateCenters(ring)) {
			double dx = gate[0] - start.getX();
			double dz = gate[1] - start.getZ();
			if (dx * dx + dz * dz < bestDistance) {
				bestDistance = dx * dx + dz * dz;
				best = new BlockPos(gate[0], start.getY(), gate[1]);
			}
		}
		return best;
	}

	/** Middle column (x, z) of every gate. */
	public static List<int[]> gateCenters(WallRing ring) {
		List<int[]> gates = new ArrayList<>();
		int n = ring.size();
		for (int i = 0; i < n; i++) {
			if (!WallRing.isGate(ring.status(i)) || WallRing.isGate(ring.status(Math.floorMod(i - 1, n)))) {
				continue;
			}
			int length = 0;
			while (length < n && WallRing.isGate(ring.status((i + length) % n))) {
				length++;
			}
			int middle = (i + length / 2) % n;
			gates.add(new int[] {ring.x(middle), ring.z(middle)});
		}
		return gates;
	}

	// ---------------------------------------------------------------- outside: farms and pens

	/**
	 * A site for a farm or pen outside the wall, within farmBeltWidth of it: most attempts start near a gate so
	 * villagers go out through it. Like {@link SiteFinder#find}, a spot without trees wins at once.
	 */
	public static Optional<BuildSite> findOutside(ServerLevel level, VillageRecord village, BuildingTemplate building, RandomSource random) {
		LVConfig config = LVConfig.get();
		WallRing ring = village.getWalls().outer();
		if (ring == null) {
			return Optional.empty();
		}
		int[] polygon = ring.getPolygon();
		double cx = 0;
		double cz = 0;
		for (int v = 0; v < polygon.length / 2; v++) {
			cx += polygon[v * 2];
			cz += polygon[v * 2 + 1];
		}
		cx /= polygon.length / 2.0;
		cz /= polygon.length / 2.0;
		List<int[]> gates = gateCenters(ring);
		int belt = config.farmBeltWidth;
		BuildSite best = null;
		for (int i = 0; i < config.siteAttempts; i++) {
			int x;
			int z;
			if (!gates.isEmpty() && i % 3 != 2) {
				int[] gate = gates.get(i % gates.size());
				Direction out = outward(gate[0], gate[1], cx, cz);
				Direction side = out.getClockWise();
				int distance = WALL_CLEARANCE + 2 + random.nextInt(Math.max(1, Math.min(NEAR_GATE, belt)));
				int lateral = random.nextInt(NEAR_GATE * 2 + 1) - NEAR_GATE;
				x = gate[0] + out.getStepX() * distance + side.getStepX() * lateral;
				z = gate[1] + out.getStepZ() * distance + side.getStepZ() * lateral;
			} else {
				int column = random.nextInt(ring.size());
				Direction out = outward(ring.x(column), ring.z(column), cx, cz);
				int distance = WALL_CLEARANCE + 2 + random.nextInt(Math.max(1, belt));
				x = ring.x(column) + out.getStepX() * distance;
				z = ring.z(column) + out.getStepZ() * distance;
			}
			for (Rotation rotation : Util.shuffledCopy(Rotation.values(), random)) {
				BoundingBox footprint = SiteFinder.footprintAt(building, x, z, rotation);
				if (!outsideWall(ring, footprint, belt)) {
					continue;
				}
				BuildSite site = SiteFinder.tryAt(level, building, x, z, rotation, config.margin);
				if (site == null) {
					continue;
				}
				if (site.treeRoots().isEmpty()) {
					return Optional.of(site);
				}
				if (best == null || site.treeRoots().size() < best.treeRoots().size()) {
					best = site;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/** Wholly outside the wall, at least WALL_CLEARANCE from it, and at most {@code belt} away from it. */
	private static boolean outsideWall(WallRing ring, BoundingBox footprint, int belt) {
		BoundingBox clearance = footprint.inflatedBy(WALL_CLEARANCE);
		if (ring.contains(footprint.getCenter().getX() + 0.5, footprint.getCenter().getZ() + 0.5)) {
			return false;
		}
		double nearest = Double.MAX_VALUE;
		BlockPos centre = footprint.getCenter();
		for (int i = 0; i < ring.size(); i++) {
			if (clearance.isInside(ring.x(i), clearance.minY(), ring.z(i))) {
				return false;
			}
			double dx = ring.x(i) - centre.getX();
			double dz = ring.z(i) - centre.getZ();
			nearest = Math.min(nearest, dx * dx + dz * dz);
		}
		double reach = belt + Math.max(footprint.getXSpan(), footprint.getZSpan()) / 2.0;
		return nearest <= reach * reach;
	}

	private static Direction outward(int x, int z, double cx, double cz) {
		double dx = x - cx;
		double dz = z - cz;
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** Forgets searches in progress (server stop). */
	public static void clear() {
		SCANS.clear();
	}
}
