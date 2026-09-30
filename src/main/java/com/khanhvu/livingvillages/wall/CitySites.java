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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
	private static final int GRID_STEP = 2;
	/** Farm and pen attempts start this close to a gate. */
	private static final int NEAR_GATE = 16;
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
		int[] candidates = new int[0];
		int templateIndex;
		int candidateIndex;
		int rotationIndex;

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

	/** The village's outermost ring is a city wall (being built or not): buildings follow the city rules. */
	public static boolean active(VillageRecord village) {
		WallRing outer = village.getWalls().outer();
		return outer != null && outer.getType() == WallRing.Type.CITY && WallBuilder.enabled();
	}

	/** Houses and workshops are built inside the walls; farms and pens outside. */
	public static boolean insideKind(@Nullable BuildingKind kind) {
		return kind != BuildingKind.FARM && kind != BuildingKind.PEN;
	}

	public static boolean isFull(VillageRecord village) {
		return active(village) && village.getWalls().outer().isFull();
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
		Scan scan = new Scan(village.getWalls().outer(), requested, smallestHome);
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
		scan.reserved = reserved;
		// Walking distance to the nearest road over the inside (to the bell when there is no road).
		int[] distance = new int[total];
		Arrays.fill(distance, Integer.MAX_VALUE);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < total; i++) {
			if (scan.road[i]) {
				distance[i] = 0;
				queue.add(i);
			}
		}
		int bellIndex = scan.index(bell.getX(), bell.getZ());
		if (queue.isEmpty() && bellIndex >= 0) {
			distance[bellIndex] = 0;
			queue.add(bellIndex);
		}
		while (!queue.isEmpty()) {
			int i = queue.poll();
			int x = i / scan.depth;
			int z = i % scan.depth;
			int[][] next = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
			for (int[] n : next) {
				if (n[0] < 0 || n[1] < 0 || n[0] >= scan.width || n[1] >= scan.depth) {
					continue;
				}
				int j = n[0] * scan.depth + n[1];
				if (scan.inside[j] && distance[j] == Integer.MAX_VALUE) {
					distance[j] = distance[i] + 1;
					queue.add(j);
				}
			}
		}
		List<Integer> spots = new ArrayList<>();
		for (int x = scan.minX; x < scan.minX + scan.width; x += GRID_STEP) {
			for (int z = scan.minZ; z < scan.minZ + scan.depth; z += GRID_STEP) {
				int i = scan.index(x, z);
				if (scan.inside[i] && !reserved[i]) {
					spots.add(i);
				}
			}
		}
		spots.sort(Comparator.comparingInt((Integer i) -> distance[i]).thenComparingDouble(i -> {
			double dx = scan.minX + i / scan.depth - bell.getX();
			double dz = scan.minZ + i % scan.depth - bell.getZ();
			return dx * dx + dz * dz;
		}));
		scan.candidates = spots.stream().mapToInt(Integer::intValue).toArray();
		scan.phase = Phase.SEARCH;
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
		Rotation[] rotations = Rotation.values();
		while (quick < QUICK_CHECKS_PER_TICK && tries < SITE_TRIES_PER_TICK) {
			if (scan.templateIndex >= scan.templates.size()) {
				finishFailed(level, registry, village, ring, scan);
				return;
			}
			BuildingTemplate building = scan.templates.get(scan.templateIndex);
			if (scan.candidateIndex >= scan.candidates.length) {
				// This building fits nowhere; the smallest house or workshop is the test for "full".
				if (building == scan.smallestHome) {
					scan.smallestHomeFailed = true;
				}
				scan.templateIndex++;
				scan.candidateIndex = 0;
				scan.rotationIndex = 0;
				if (scan.templateIndex >= scan.templates.size() && !scan.smallestHomeFailed && scan.smallestHome != null
						&& !scan.templates.contains(scan.smallestHome)) {
					scan.templates.add(scan.smallestHome);
				}
				continue;
			}
			int spot = scan.candidates[scan.candidateIndex];
			int cx = scan.minX + spot / scan.depth;
			int cz = scan.minZ + spot % scan.depth;
			Rotation rotation = rotations[scan.rotationIndex];
			if (++scan.rotationIndex >= rotations.length) {
				scan.rotationIndex = 0;
				scan.candidateIndex++;
			}
			quick++;
			BoundingBox footprint = SiteFinder.footprintAt(building, cx, cz, rotation);
			if (!quickFits(level, ring, scan, footprint, config)) {
				continue;
			}
			tries++;
			BuildSite site = SiteFinder.tryAt(level, building, cx, cz, rotation, config.infillMargin);
			if (site != null) {
				SCANS.remove(village.getId());
				LivingVillages.debug("Village {}: site inside the walls for {} at {}", village.getId(), building.id(), site.origin());
				VillageTicker.beginProject(level, registry, village, building, site);
				return;
			}
		}
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
		if (!active(village)) {
			return null;
		}
		WallRing ring = village.getWalls().outer();
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
