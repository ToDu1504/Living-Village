package com.khanhvu.livingvillages.wall;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.build.TreeFeller;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.chronicle.ChronicleEntry;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds and keeps the walls of villages near players (spec v3 §5). v3-GĐ 1: a palisade from level Village, with a
 * gate wherever a road crosses it, moved outwards when the village grows past it (new palisade first, then the old
 * one taken down). v3-GĐ 2: from level Town a stone city wall replaces it on the same line, column by column, with
 * 5×5 watch towers and arches over the gates; level City raises it and adds battlements. Masons build, each at
 * {@code wallBlocksPerSecond}; without a mason the village builds at half that rate. Every block placed is
 * remembered: one a player breaks stays open, one lost otherwise (explosion, fire) is built again.
 */
public final class WallBuilder {
	private static final String REGROWTH = "regrowth";
	private static final int REPAIR_INTERVAL = 20;
	private static final int REPAIR_BATCH = 32;
	private static final int TAKE_DOWN_INTERVAL = 5;
	private static final double REACH = 4.0;
	/** A mason picks the nearest open unit among this many next in the building order. */
	private static final int CLAIM_WINDOW = 12;
	private static final double MAX_POINTS = 2.0;
	/** How far below the surface the ground of a wall column is looked for (through our own blocks, plants, trees). */
	private static final int MAX_GROUND_DEPTH = 48;
	/** Deepest foundation under a tower. */
	private static final int MAX_TOWER_FOUNDATION = 8;
	/** Towers keep this many columns (plus half a gate) away from gates. */
	private static final int TOWER_GATE_CLEARANCE = 3;
	private static final int TOWER_SHIFT = 3;
	/** Tower centres at least this far apart. */
	private static final int TOWER_MIN_DISTANCE = 7;

	/** What working on one unit did. */
	private enum Result {
		/** A block was placed, removed or a tree felled: costs one point. */
		WORKED,
		/** The unit is settled, or nothing to do this time, without work. */
		SETTLED,
		/** Not loaded: try again later. */
		WAIT
	}

	/** What happened to one block of a shape. */
	private enum Place {
		PLACED,
		/** Already there. */
		SAME,
		/** Something that is not ours and may not be replaced is in the way. */
		BLOCKED,
		/** A player broke our block here: it stays open. */
		ABANDONED
	}

	private static final class Worker {
		int unit = -1;
		long since;
		double points;
	}

	/** Runtime state of one village's walls; not saved (claims are simply made again after a restart). */
	private static final class Site {
		final Map<UUID, Worker> workers = new HashMap<>();
		/** Units masons could not reach: built without anyone, at the village rate. */
		final IntLinkedOpenHashSet remote = new IntLinkedOpenHashSet();
		double remotePoints;
		long[] repairKeys = new long[0];
		int repairCursor;
		@Nullable
		LongArrayList takeDown;
	}

	private static final Map<UUID, Site> SITES = new HashMap<>();
	private static boolean deferLogged;

	private WallBuilder() {
	}

	/** Walls are built unless switched off, or left to Regrowth when asked to (spec v3 §3). */
	public static boolean enabled() {
		LVConfig config = LVConfig.get();
		if (!config.wallsEnabled) {
			return false;
		}
		if (config.deferToRegrowth && FabricLoader.getInstance().isModLoaded(REGROWTH)) {
			if (!deferLogged) {
				deferLogged = true;
				LivingVillages.LOGGER.info("[LivingVillages] Regrowth is installed: village walls are left to it (deferToRegrowth)");
			}
			return false;
		}
		return true;
	}

	/** Whether the villager is building a wall now (for what villagers say). */
	public static boolean isWorking(UUID villager) {
		for (Site site : SITES.values()) {
			Worker worker = site.workers.get(villager);
			if (worker != null && worker.unit >= 0) {
				return true;
			}
		}
		return false;
	}

	public static void register() {
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			WallRing outer = e.village().getWalls().outer();
			if (outer != null && outer.getType() == WallRing.Type.PALISADE && !VillageBoundary.containsBox(outer.getPolygon(), e.footprint())) {
				e.village().getWalls().setMoveWanted(true);
				VillageRegistry.get(e.level()).setDirty();
			}
		});
		// A wall block a player breaks is theirs to leave open (spec v3 §5): its unit is looked at again and skips it.
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel level)) {
				return;
			}
			long key = pos.asLong();
			for (VillageRecord village : VillageRegistry.get(level).getVillages()) {
				VillageWalls walls = village.getWalls();
				if (walls.getBlocks().containsKey(key)) {
					int value = walls.getBlocks().remove(key);
					walls.getAbandoned().add(key);
					WallRing ring = walls.ring(VillageWalls.ringOf(value));
					if (ring != null && ring != walls.getRetiring() && VillageWalls.columnOf(value) < ring.units()) {
						ring.reopen(VillageWalls.columnOf(value));
					}
					VillageRegistry.get(level).setDirty();
					LivingVillages.debug("Village {}: wall block at {} broken by {}, left open", village.getId(), pos, player.getName().getString());
					return;
				}
			}
		});
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRegistry registry, VillageRecord village, boolean manage) {
		VillageWalls walls = village.getWalls();
		if (!enabled() || walls.isPaused()) {
			Site site = SITES.remove(village.getId());
			if (site != null) {
				releaseAll(level, site);
			}
			return;
		}
		Site site = SITES.computeIfAbsent(village.getId(), id -> new Site());
		if (manage) {
			plan(level, registry, village, walls);
		}
		long time = level.getGameTime();
		if (time % REPAIR_INTERVAL == 0) {
			checkRepairs(level, registry, walls, site);
		}
		WallRing ring = walls.outer();
		if (ring == null) {
			return;
		}
		if (ring.openUnits() > 0) {
			if (VillageTicker.isWorkTime(level) && level.getRaidAt(village.getBellPos()) == null) {
				build(level, registry, village, walls, ring, site);
			}
			return;
		}
		releaseAll(level, site);
		if (!ring.isCompleted()) {
			ring.setCompleted(time);
			registry.setDirty();
			if (ring.getType() == WallRing.Type.CITY) {
				Chronicle.add(level, village, Chronicle.Notice.BIG, "livingvillages.chronicle.wall_finished", ChronicleEntry.keyArg(ring.getType().langKey()));
			} else if (walls.getRetiring() == null) {
				Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_finished", ChronicleEntry.keyArg(ring.getType().langKey()));
			}
		}
		if (walls.getRetiring() != null && time % TAKE_DOWN_INTERVAL == 0 && VillageTicker.isWorkTime(level)) {
			takeDown(level, registry, village, walls, site);
		}
	}

	// ---------------------------------------------------------------- planning

	/**
	 * The walls follow the village level: a palisade from level Village (moved out as the village grows), a city wall
	 * on the palisade's line from level Town, raised with battlements at level City.
	 */
	private static void plan(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls) {
		LVConfig config = LVConfig.get();
		int villageLevel = village.getLevel();
		if (!VillageLevel.enabled() || villageLevel < Math.min(config.palisadeMinLevel, config.cityWallMinLevel) || village.getVillageType() == null) {
			return;
		}
		WallRing outer = walls.outer();
		if (outer == null) {
			// A village that is already a town when first seen skips the palisade (farms and pens stay outside).
			boolean city = villageLevel >= config.cityWallMinLevel;
			WallRing.Type type = city ? WallRing.Type.CITY : WallRing.Type.PALISADE;
			Predicate<BoundingBox> plots = city ? plot -> !village.getFieldPlots().contains(plot) : plot -> true;
			WallRing ring = createRing(level, village, walls, type, VillageBoundary.compute(level, village, plots), null);
			if (ring != null) {
				walls.getRings().add(ring);
				registry.setDirty();
				announceStart(level, village, ring);
			}
			return;
		}
		if (outer.getType() == WallRing.Type.PALISADE) {
			if (villageLevel >= config.cityWallMinLevel && outer.openUnits() == 0 && walls.getRetiring() == null) {
				upgradeToCity(level, registry, village, walls, outer);
			} else {
				movePalisade(level, registry, village, walls, outer);
			}
			return;
		}
		// Level City: the wall grows taller and gets battlements.
		if (villageLevel >= VillageLevel.MAX && !outer.hasBattlements()) {
			outer.setHeight(config.cityWallHeightMax, true);
			for (int i = 0; i < outer.size(); i++) {
				if (outer.status(i) == WallRing.DONE || outer.status(i) == WallRing.EXPOSED) {
					outer.setStatus(i, WallRing.PENDING);
				}
			}
			registry.setDirty();
			Chronicle.add(level, village, Chronicle.Notice.BIG, "livingvillages.chronicle.wall_raised");
			LivingVillages.debug("Village {}: city wall raised to {}", village.getId(), config.cityWallHeightMax);
		}
	}

	/** v3-GĐ 1: the palisade follows the village, at most once per palisadeMoveCooldown, never while one is going up or down. */
	private static void movePalisade(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls, WallRing outer) {
		if (!walls.isMoveWanted() || outer.openUnits() > 0 || walls.getRetiring() != null
				|| level.getGameTime() - outer.getCreatedTick() < LVConfig.get().palisadeMoveCooldown) {
			return;
		}
		if (!anyPlotOutside(village, outer)) {
			walls.setMoveWanted(false);
			registry.setDirty();
			return;
		}
		WallRing ring = createRing(level, village, walls, WallRing.Type.PALISADE, VillageBoundary.compute(level, village, plot -> true), null);
		if (ring == null) {
			return; // part of the new line is not loaded: next time
		}
		walls.getRings().set(walls.getRings().size() - 1, ring);
		walls.setRetiring(outer);
		walls.setMoveWanted(false);
		registry.setDirty();
		Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_moving");
		LivingVillages.debug("Village {}: palisade moves out, {} columns", village.getId(), ring.size());
	}

	/**
	 * v3-GĐ 2: the city wall goes up on the palisade's line (a fresh outline if buildings stand outside it), keeping
	 * its gates. Each column's fence is replaced by stone as the column is built; what is left of the palisade is
	 * taken down once the city wall stands.
	 */
	private static void upgradeToCity(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls, WallRing palisade) {
		boolean outside = walls.isMoveWanted() && anyPlotOutside(village, palisade);
		int[] polygon = outside ? VillageBoundary.compute(level, village, plot -> true) : palisade.getPolygon();
		WallRing city = createRing(level, village, walls, WallRing.Type.CITY, polygon, outside ? null : palisade);
		if (city == null) {
			return;
		}
		walls.getRings().set(walls.getRings().size() - 1, city);
		walls.setRetiring(palisade);
		walls.setMoveWanted(false);
		registry.setDirty();
		announceStart(level, village, city);
	}

	private static boolean anyPlotOutside(VillageRecord village, WallRing ring) {
		return village.getPlots().stream().anyMatch(plot -> !VillageBoundary.containsBox(ring.getPolygon(), plot));
	}

	private static void announceStart(ServerLevel level, VillageRecord village, WallRing ring) {
		if (ring.getType() == WallRing.Type.CITY) {
			Chronicle.add(level, village, Chronicle.Notice.BIG, "livingvillages.chronicle.city_wall_started");
		} else {
			Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_started", ChronicleEntry.keyArg(ring.getType().langKey()));
		}
		LivingVillages.debug("Village {}: {} planned, {} columns, {} gate(s), {} tower(s)", village.getId(), ring.getType().getSerializedName(),
				ring.size(), ring.gateCount(), ring.getTowers().size());
	}

	/**
	 * A new ring along the polygon, with the gates of {@code gatesFrom} (same outline) or gates where roads cross it
	 * (one nearest the bell when none does), and towers for a city wall. Null while part of the line is not loaded.
	 */
	@Nullable
	private static WallRing createRing(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing.Type type, int[] polygon,
			@Nullable WallRing gatesFrom) {
		LVConfig config = LVConfig.get();
		WallRing ring = new WallRing(walls.takeRingId(), type, polygon, level.getGameTime());
		int n = ring.size();
		for (int i = 0; i < n; i++) {
			if (!level.hasChunk(ring.x(i) >> 4, ring.z(i) >> 4)) {
				return null;
			}
		}
		int half = config.gateWidth / 2;
		if (gatesFrom != null && gatesFrom.size() == n) {
			for (int i = 0; i < n; i++) {
				if (WallRing.isGate(gatesFrom.status(i))) {
					ring.setStatus(i, WallRing.GATE);
				}
			}
		} else {
			Set<Block> roads = RoadBuilder.roadBlocks();
			boolean any = false;
			for (int i = 0; i < n; i++) {
				int x = ring.x(i);
				int z = ring.z(i);
				if (roads.contains(level.getBlockState(new BlockPos(x, groundOf(level, walls, x, z), z)).getBlock())) {
					markGate(ring, i, half);
					any = true;
				}
			}
			if (!any) {
				markGate(ring, nearestColumn(ring, village.getBellPos()), half);
			}
		}
		if (type == WallRing.Type.CITY) {
			boolean city = village.getLevel() >= VillageLevel.MAX;
			ring.setHeight(city ? config.cityWallHeightMax : config.cityWallHeight, city);
			placeTowers(level, village, walls, ring);
		}
		return ring;
	}

	private static int nearestColumn(WallRing ring, BlockPos target) {
		int nearest = 0;
		long best = Long.MAX_VALUE;
		for (int i = 0; i < ring.size(); i++) {
			long dx = ring.x(i) - target.getX();
			long dz = ring.z(i) - target.getZ();
			if (dx * dx + dz * dz < best) {
				best = dx * dx + dz * dz;
				nearest = i;
			}
		}
		return nearest;
	}

	/** Opens a gate of gateWidth columns centred on {@code column}. */
	private static void markGate(WallRing ring, int column, int half) {
		int n = ring.size();
		for (int d = -half; d <= half; d++) {
			int i = Math.floorMod(column + d, n);
			if (!WallRing.isGate(ring.status(i))) {
				ring.setStatus(i, WallRing.GATE);
			}
		}
	}

	// ---------------------------------------------------------------- towers

	/**
	 * Towers at the corners of the outline and at most towerSpacing columns apart, kept away from gates, moved a few
	 * columns when a spot does not fit (water, a building, too steep) and left out when none does.
	 */
	private static void placeTowers(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring) {
		LVConfig config = LVConfig.get();
		int n = ring.size();
		int[] polygon = ring.getPolygon();
		TreeSet<Integer> wanted = new TreeSet<>();
		for (int v = 0; v < polygon.length / 2; v++) {
			for (int i = 0; i < n; i++) {
				if (ring.x(i) == polygon[v * 2] && ring.z(i) == polygon[v * 2 + 1]) {
					wanted.add(i);
					break;
				}
			}
		}
		if (wanted.isEmpty()) {
			wanted.add(0);
		}
		List<Integer> corners = new ArrayList<>(wanted);
		for (int k = 0; k < corners.size(); k++) {
			int a = corners.get(k);
			int b = corners.get((k + 1) % corners.size());
			int gap = Math.floorMod(b - a, n);
			if (gap == 0) {
				gap = n;
			}
			int extra = (gap - 1) / config.towerSpacing;
			for (int j = 1; j <= extra; j++) {
				wanted.add(Math.floorMod(a + gap * j / (extra + 1), n));
			}
		}
		double cx = 0;
		double cz = 0;
		for (int v = 0; v < polygon.length / 2; v++) {
			cx += polygon[v * 2];
			cz += polygon[v * 2 + 1];
		}
		cx /= polygon.length / 2.0;
		cz /= polygon.length / 2.0;
		for (int wantedColumn : wanted) {
			for (int shift = 0; shift <= TOWER_SHIFT * 2; shift++) {
				int offset = (shift + 1) / 2 * (shift % 2 == 0 ? 1 : -1); // 0, -1, 1, -2, 2...
				int column = Math.floorMod(wantedColumn + offset, n);
				if (nearGate(ring, column, config.gateWidth / 2 + TOWER_GATE_CLEARANCE)) {
					continue;
				}
				int x = ring.x(column);
				int z = ring.z(column);
				WallRing.Tower tower = new WallRing.Tower(x, z, groundOf(level, walls, x, z), outward(x, z, cx, cz));
				if (overlapsTower(ring, tower) || !towerFits(level, walls, tower)) {
					continue;
				}
				ring.addTower(tower);
				for (int i = 0; i < n; i++) {
					if (tower.covers(ring.x(i), ring.z(i)) && !WallRing.isGate(ring.status(i))) {
						ring.setStatus(i, WallRing.TOWER);
					}
				}
				break;
			}
		}
	}

	private static boolean nearGate(WallRing ring, int column, int distance) {
		for (int d = -distance; d <= distance; d++) {
			if (WallRing.isGate(ring.status(Math.floorMod(column + d, ring.size())))) {
				return true;
			}
		}
		return false;
	}

	private static boolean overlapsTower(WallRing ring, WallRing.Tower tower) {
		for (WallRing.Tower other : ring.getTowers()) {
			int dx = other.centerX() - tower.centerX();
			int dz = other.centerZ() - tower.centerZ();
			if (dx * dx + dz * dz < TOWER_MIN_DISTANCE * TOWER_MIN_DISTANCE) {
				return true;
			}
		}
		return false;
	}

	/** Out of the village, along the main axis from the outline's centre. */
	private static Direction outward(int x, int z, double cx, double cz) {
		double dx = x - cx;
		double dz = z - cz;
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/**
	 * A tower fits where every column of its square is loaded, outside plots, over ground no deeper than a
	 * foundation can reach, and its whole volume holds only things it may replace: air, plants, natural ground,
	 * natural trees, our own wall blocks. No water or lava.
	 */
	private static boolean towerFits(ServerLevel level, VillageWalls walls, WallRing.Tower tower) {
		int height = WallShapes.towerHeight();
		for (int[] cell : WallShapes.footprint()) {
			BlockPos base = WallShapes.at(tower, cell[0], cell[1], 0);
			if (!level.hasChunk(base.getX() >> 4, base.getZ() >> 4) || insidePlot(level, base.getX(), base.getZ())) {
				return false;
			}
			int ground = groundOf(level, walls, base.getX(), base.getZ());
			if (ground < tower.baseY - MAX_TOWER_FOUNDATION || ground > tower.baseY + height) {
				return false;
			}
			for (int y = Math.min(ground, tower.baseY); y <= tower.baseY + height + 1; y++) {
				BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
				BlockState state = level.getBlockState(pos);
				if (!state.getFluidState().isEmpty()) {
					return false;
				}
				boolean ok = walls.getBlocks().containsKey(pos.asLong()) || canReplace(state) || SiteFinder.isNaturalGround(state)
						|| (LVConfig.get().allowTreeClearing && TreeFeller.isTreeLog(state));
				if (!ok) {
					return false;
				}
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- building

	private static void build(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls, WallRing ring, Site site) {
		LVConfig config = LVConfig.get();
		double rate = config.wallBlocksPerSecond * config.speedMultiplier / 20.0;
		List<Villager> masons = masons(level, village);
		Set<UUID> present = new HashSet<>();
		boolean changed = false;
		for (Villager mason : masons) {
			present.add(mason.getUUID());
			Worker worker = site.workers.computeIfAbsent(mason.getUUID(), id -> new Worker());
			if (worker.unit < 0 || worker.unit >= ring.units() || !ring.isOpen(worker.unit)) {
				worker.unit = claim(ring, site, mason);
				worker.since = level.getGameTime();
				if (worker.unit < 0) {
					release(mason);
					continue;
				}
				LivingVillages.debug("Village {}: mason {} takes wall unit {}", village.getId(), mason.getUUID(), worker.unit);
			}
			BlockPos stand = standingPos(level, walls, ring, worker.unit);
			if (stand == null) {
				continue;
			}
			BuilderAssignment.walkTo(mason, stand, stand);
			double dx = mason.getX() - stand.getX() - 0.5;
			double dz = mason.getZ() - stand.getZ() - 0.5;
			if (dx * dx + dz * dz > REACH * REACH) {
				if (level.getGameTime() - worker.since > config.workTimeoutTicks) {
					site.remote.add(worker.unit); // cannot get there: built without them
					worker.unit = -1;
				}
				continue;
			}
			worker.since = level.getGameTime();
			worker.points = Math.min(MAX_POINTS, worker.points + rate);
			while (worker.points >= 1.0 && ring.isOpen(worker.unit) && VillageTicker.hasBudget(level)) {
				Result result = work(level, village, walls, ring, worker.unit);
				if (result == Result.WAIT) {
					break;
				}
				changed = true;
				if (result == Result.WORKED) {
					VillageTicker.useBudget(level);
					worker.points -= 1.0;
					mason.swing(InteractionHand.MAIN_HAND);
					holdBlock(mason, village, ring);
				}
			}
		}
		// Masons who left (other work, sleep, gone) are let go.
		for (Iterator<Map.Entry<UUID, Worker>> it = site.workers.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Worker> entry = it.next();
			if (!present.contains(entry.getKey())) {
				if (level.getEntity(entry.getKey()) instanceof Villager villager) {
					release(villager);
				}
				it.remove();
			}
		}
		// Without masons, and for units no mason can reach, the village builds at half the rate.
		if (masons.isEmpty() || !site.remote.isEmpty()) {
			site.remotePoints = Math.min(MAX_POINTS, site.remotePoints + rate / 2.0);
			while (site.remotePoints >= 1.0 && VillageTicker.hasBudget(level)) {
				int unit = remoteUnit(ring, site, masons.isEmpty());
				if (unit < 0) {
					break;
				}
				Result result = work(level, village, walls, ring, unit);
				if (result == Result.WAIT) {
					site.remote.remove(unit);
					break;
				}
				changed = true;
				if (!ring.isOpen(unit)) {
					site.remote.remove(unit);
				}
				if (result == Result.WORKED) {
					VillageTicker.useBudget(level);
					site.remotePoints -= 1.0;
				}
			}
		}
		if (changed) {
			registry.setDirty();
		}
	}

	/** Where the mason stands for a unit: on the column, or inside the city in front of a tower's door. */
	@Nullable
	private static BlockPos standingPos(ServerLevel level, VillageWalls walls, WallRing ring, int unit) {
		int x = ring.unitX(unit);
		int z = ring.unitZ(unit);
		if (ring.isTowerUnit(unit)) {
			WallRing.Tower tower = ring.tower(unit);
			x -= tower.out.getStepX();
			z -= tower.out.getStepZ();
		}
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		return new BlockPos(x, groundOf(level, walls, x, z) + 1, z);
	}

	/** Adult masons free for the walls: not building a house, not paving, not the leader, awake and not trading. */
	private static List<Villager> masons(ServerLevel level, VillageRecord village) {
		BuildProject project = village.getProject();
		UUID builder = project == null ? null : project.getBuilderUuid();
		return VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village)).stream()
				.filter(v -> v.getVillagerData().getProfession() == VillagerProfession.MASON && !v.isSleeping() && !v.isTrading()
						&& !v.getUUID().equals(builder) && !v.getUUID().equals(village.getLeaderUuid()) && !RoadBuilder.isPaving(v.getUUID()))
				.toList();
	}

	/** The nearest open unit, among the next few in building order, that no other mason works on; -1 if none. */
	private static int claim(WallRing ring, Site site, Villager mason) {
		Set<Integer> taken = new HashSet<>();
		for (Worker other : site.workers.values()) {
			taken.add(other.unit);
		}
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		int seen = 0;
		for (int unit : ring.order()) {
			if (!ring.isOpen(unit) || taken.contains(unit) || site.remote.contains(unit)) {
				continue;
			}
			double dx = mason.getX() - ring.unitX(unit) - 0.5;
			double dz = mason.getZ() - ring.unitZ(unit) - 0.5;
			if (dx * dx + dz * dz < bestDist) {
				bestDist = dx * dx + dz * dz;
				best = unit;
			}
			if (++seen >= CLAIM_WINDOW) {
				break;
			}
		}
		return best;
	}

	/** The next unit built without a mason: unreachable ones first, then (with no mason at all) the next open one. */
	private static int remoteUnit(WallRing ring, Site site, boolean noMasons) {
		while (!site.remote.isEmpty()) {
			int unit = site.remote.firstInt();
			if (unit < ring.units() && ring.isOpen(unit)) {
				return unit;
			}
			site.remote.remove(unit);
		}
		if (noMasons) {
			for (int unit : ring.order()) {
				if (ring.isOpen(unit)) {
					return unit;
				}
			}
		}
		return -1;
	}

	/** One step of work on a unit: at most one block placed or removed, or one tree felled. */
	private static Result work(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int unit) {
		if (ring.isTowerUnit(unit)) {
			return workTower(level, village, walls, ring, unit);
		}
		int x = ring.x(unit);
		int z = ring.z(unit);
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return Result.WAIT;
		}
		if (ring.status(unit) == WallRing.GATE) {
			return workArch(level, village, walls, ring, unit);
		}
		return ring.getType() == WallRing.Type.PALISADE
				? workPalisade(level, village, walls, ring, unit)
				: workCityColumn(level, village, walls, ring, unit);
	}

	/**
	 * Checks shared by every wall column: fells a tree standing in it, turns it into a gate when a road now crosses
	 * it, leaves it open over water, man-made ground, plots or a cliff. Null when the column may be built.
	 */
	@Nullable
	private static Result columnObstacle(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int column, int groundY,
			int height) {
		LVConfig config = LVConfig.get();
		int x = ring.x(column);
		int z = ring.z(column);
		for (int dy = 1; dy <= height; dy++) {
			BlockPos pos = new BlockPos(x, groundY + dy, z);
			if (config.allowTreeClearing && TreeFeller.isTreeLog(level.getBlockState(pos))) {
				TreeFeller.Tree tree = TreeFeller.findTree(level, pos, config.maxTreeLogs);
				if (tree != null) {
					fell(level, village, tree);
					return Result.WORKED; // the column is built next time
				}
			}
		}
		BlockState ground = level.getBlockState(new BlockPos(x, groundY, z));
		if (RoadBuilder.roadBlocks().contains(ground.getBlock())) {
			markGate(ring, column, config.gateWidth / 2); // a road made since the ring was planned
			return Result.SETTLED;
		}
		if (!ground.getFluidState().isEmpty() || !SiteFinder.isNaturalGround(ground) || insidePlot(level, x, z)
				|| tooSteep(level, walls, ring, column, groundY)) {
			ring.setStatus(column, WallRing.WEAK);
			return Result.SETTLED;
		}
		return null;
	}

	/** A palisade column: one fence on the ground. */
	private static Result workPalisade(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int column) {
		int groundY = groundOf(level, walls, ring.x(column), ring.z(column));
		Result obstacle = columnObstacle(level, village, walls, ring, column, groundY, 1);
		if (obstacle != null) {
			return obstacle;
		}
		BlockPos cell = new BlockPos(ring.x(column), groundY + 1, ring.z(column));
		return switch (place(level, walls, ring, column, cell, WallShapes.blocks(village).palisade().defaultBlockState(), false)) {
			case PLACED -> {
				ring.setStatus(column, WallRing.DONE);
				yield Result.WORKED;
			}
			case SAME -> {
				ring.setStatus(column, WallRing.DONE);
				yield Result.SETTLED;
			}
			case BLOCKED, ABANDONED -> {
				ring.setStatus(column, WallRing.WEAK);
				yield Result.SETTLED;
			}
		};
	}

	/**
	 * A city wall column, bottom up: a foundation block, the main block up to the wall height (one more next to a
	 * gate, to carry the arch), and a battlement on every other column at level City. The palisade fence standing
	 * there is replaced by the first block. A hole in the lower two blocks (a player's break, a block in the way)
	 * makes the column a weak point; so does ground right outside about as high as the wall top.
	 */
	private static Result workCityColumn(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int column) {
		int groundY = groundOf(level, walls, ring.x(column), ring.z(column));
		int height = columnHeight(ring, column);
		Result obstacle = columnObstacle(level, village, walls, ring, column, groundY, height + 1);
		if (obstacle != null) {
			return obstacle;
		}
		WallShapes.Blocks4 blocks = WallShapes.blocks(village);
		boolean hole = false;
		for (int dy = 1; dy <= height + 1; dy++) {
			BlockState state;
			if (dy == height + 1) {
				if (!ring.hasBattlements() || column % 2 != 0) {
					break;
				}
				state = blocks.top().defaultBlockState();
			} else {
				state = (dy == 1 ? blocks.foundation() : blocks.main()).defaultBlockState();
			}
			Place result = place(level, walls, ring, column, new BlockPos(ring.x(column), groundY + dy, ring.z(column)), state, false);
			if (result == Place.PLACED) {
				return Result.WORKED;
			}
			if (result == Place.BLOCKED && dy == 1) {
				ring.setStatus(column, WallRing.WEAK); // something not ours stands on the line
				return Result.SETTLED;
			}
			if (result != Place.SAME && dy <= 2) {
				hole = true;
			}
		}
		ring.setStatus(column, hole ? WallRing.WEAK : exposed(level, walls, ring, column, groundY + height) ? WallRing.EXPOSED : WallRing.DONE);
		return Result.SETTLED;
	}

	/** Wall height of a column: the ring's height, but high enough next to a gate to carry the arch. */
	private static int columnHeight(WallRing ring, int column) {
		int n = ring.size();
		boolean besideGate = WallRing.isGate(ring.status(Math.floorMod(column - 1, n))) || WallRing.isGate(ring.status(Math.floorMod(column + 1, n)));
		return besideGate ? Math.max(ring.getHeight(), LVConfig.get().cityWallHeight + 1) : ring.getHeight();
	}

	/** Ground within two blocks outside the wall is as high as the wall top minus one: monsters can jump in. */
	private static boolean exposed(ServerLevel level, VillageWalls walls, WallRing ring, int column, int topY) {
		int[] polygon = ring.getPolygon();
		double cx = 0;
		double cz = 0;
		for (int v = 0; v < polygon.length / 2; v++) {
			cx += polygon[v * 2];
			cz += polygon[v * 2 + 1];
		}
		Direction out = outward(ring.x(column), ring.z(column), cx / (polygon.length / 2.0), cz / (polygon.length / 2.0));
		for (int d = 1; d <= 2; d++) {
			int x = ring.x(column) + out.getStepX() * d;
			int z = ring.z(column) + out.getStepZ() * d;
			if (level.hasChunk(x >> 4, z >> 4) && groundOf(level, walls, x, z) >= topY - 1) {
				return true;
			}
		}
		return false;
	}

	/** The arch over a city gate: one main block across the gate, the wall height plus one above the highest ground. */
	private static Result workArch(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int column) {
		if (ring.getType() != WallRing.Type.CITY) {
			return Result.SETTLED;
		}
		int n = ring.size();
		List<Integer> run = new ArrayList<>();
		run.add(column);
		for (int i = Math.floorMod(column - 1, n); WallRing.isGate(ring.status(i)) && !run.contains(i); i = Math.floorMod(i - 1, n)) {
			run.add(i);
		}
		for (int i = Math.floorMod(column + 1, n); WallRing.isGate(ring.status(i)) && !run.contains(i); i = Math.floorMod(i + 1, n)) {
			run.add(i);
		}
		int top = Integer.MIN_VALUE;
		for (int i : run) {
			if (!level.hasChunk(ring.x(i) >> 4, ring.z(i) >> 4)) {
				return Result.WAIT;
			}
			top = Math.max(top, groundOf(level, walls, ring.x(i), ring.z(i)));
		}
		BlockPos lintel = new BlockPos(ring.x(column), top + LVConfig.get().cityWallHeight + 1, ring.z(column));
		if (place(level, walls, ring, column, lintel, WallShapes.blocks(village).main().defaultBlockState(), false) == Place.PLACED) {
			return Result.WORKED;
		}
		ring.setStatus(column, WallRing.GATE_DONE);
		return Result.SETTLED;
	}

	/** A tower: foundations down to the ground, then its shape bottom up; natural ground in the way is dug out. */
	private static Result workTower(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int unit) {
		WallRing.Tower tower = ring.tower(unit);
		if (!level.hasChunk(tower.centerX() >> 4, tower.centerZ() >> 4) || !level.hasChunk(tower.x >> 4, tower.z >> 4)) {
			return Result.WAIT;
		}
		LVConfig config = LVConfig.get();
		WallShapes.Blocks4 blocks = WallShapes.blocks(village);
		List<WallShapes.Piece> pieces = new ArrayList<>();
		for (int[] cell : WallShapes.footprint()) {
			for (int dy = 0; dy >= -MAX_TOWER_FOUNDATION; dy--) {
				BlockPos pos = WallShapes.at(tower, cell[0], cell[1], dy);
				BlockState state = level.getBlockState(pos);
				if (!walls.getBlocks().containsKey(pos.asLong()) && !canReplace(state)) {
					break; // the ground
				}
				pieces.add(new WallShapes.Piece(pos, blocks.foundation().defaultBlockState()));
			}
		}
		pieces.sort(Comparator.comparingInt(piece -> piece.pos().getY()));
		pieces.addAll(WallShapes.tower(tower, blocks));
		for (WallShapes.Piece piece : pieces) {
			BlockState current = level.getBlockState(piece.pos());
			if (config.allowTreeClearing && TreeFeller.isTreeLog(current)) {
				TreeFeller.Tree tree = TreeFeller.findTree(level, piece.pos(), config.maxTreeLogs);
				if (tree != null) {
					fell(level, village, tree);
					return Result.WORKED;
				}
			}
			Place result = piece.state() == null
					? clear(level, walls, piece.pos())
					: place(level, walls, ring, unit, piece.pos(), piece.state(), true);
			if (result == Place.PLACED) {
				return Result.WORKED;
			}
		}
		ring.setTowerStatus(unit, WallRing.DONE);
		return Result.SETTLED;
	}

	/**
	 * Puts one wall block in place. Our own blocks (an old palisade) and air, plants, natural leaves, saplings — and,
	 * for towers, natural ground — may be replaced; anything else blocks. A position a player broke stays open.
	 */
	private static Place place(ServerLevel level, VillageWalls walls, WallRing ring, int unit, BlockPos pos, BlockState state,
			boolean naturalGroundToo) {
		long key = pos.asLong();
		if (walls.getAbandoned().contains(key)) {
			return Place.ABANDONED;
		}
		BlockState current = level.getBlockState(pos);
		boolean ours = walls.getBlocks().containsKey(key);
		if (current.getBlock() == state.getBlock()) {
			if (ours) {
				walls.addBlock(key, ring.getId(), unit);
			}
			return Place.SAME;
		}
		boolean replaceable = ours || canReplace(current)
				|| (naturalGroundToo && SiteFinder.isNaturalGround(current) && current.getFluidState().isEmpty());
		if (!replaceable) {
			return Place.BLOCKED;
		}
		BlockState placed = Block.updateFromNeighbourShapes(state, level, pos);
		level.setBlock(pos, placed, Block.UPDATE_ALL);
		walls.addBlock(key, ring.getId(), unit);
		SoundType sound = placed.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 4.0F, sound.getPitch() * 0.8F);
		return Place.PLACED;
	}

	/** Empties a position a tower needs open: our own blocks, plants and natural ground go; anything else stays. */
	private static Place clear(ServerLevel level, VillageWalls walls, BlockPos pos) {
		BlockState current = level.getBlockState(pos);
		if (current.isAir()) {
			return Place.SAME;
		}
		long key = pos.asLong();
		boolean ours = walls.getBlocks().containsKey(key);
		if (!ours && !canReplace(current) && !(SiteFinder.isNaturalGround(current) && current.getFluidState().isEmpty())) {
			return Place.BLOCKED;
		}
		if (ours) {
			walls.getBlocks().remove(key);
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		return Place.PLACED;
	}

	/** Air, plants, snow layers, natural leaves and saplings (a replanted one on the line) may give way to the wall. */
	private static boolean canReplace(BlockState state) {
		return SiteFinder.isClearable(state) || TreeFeller.isNaturalLeaves(state) || state.is(BlockTags.SAPLINGS);
	}

	/**
	 * Top ground block of a column, looking through our own wall blocks, plants and (when trees may be felled) trees:
	 * the ground a wall column stands on, whatever has been built on it already.
	 */
	public static int groundOf(ServerLevel level, VillageWalls walls, int x, int z) {
		boolean trees = LVConfig.get().allowTreeClearing;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < MAX_GROUND_DEPTH && y > level.getMinBuildHeight(); i++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			boolean skip = walls.getBlocks().containsKey(pos.asLong()) || SiteFinder.isClearable(state)
					|| (trees && (TreeFeller.isTreeLog(state) || TreeFeller.isNaturalLeaves(state)));
			if (!skip) {
				break;
			}
			y--;
		}
		return y;
	}

	/** A cliff: the ground differs from the previous column by more than maxWallStep. */
	private static boolean tooSteep(ServerLevel level, VillageWalls walls, WallRing ring, int column, int groundY) {
		int previous = Math.floorMod(column - 1, ring.size());
		int px = ring.x(previous);
		int pz = ring.z(previous);
		if (!level.hasChunk(px >> 4, pz >> 4)) {
			return false;
		}
		return Math.abs(groundOf(level, walls, px, pz) - groundY) > LVConfig.get().maxWallStep;
	}

	private static boolean insidePlot(ServerLevel level, int x, int z) {
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot.isInside(x, plot.minY(), z)) {
					return true;
				}
			}
			BuildProject project = other.getProject();
			if (project != null && project.getFootprint().isInside(x, project.getFootprint().minY(), z)) {
				return true;
			}
		}
		return false;
	}

	/** Fells a natural tree in the way, like on building sites (no drops), and replants it nearby later. */
	private static void fell(ServerLevel level, VillageRecord village, TreeFeller.Tree tree) {
		ResourceLocation sapling = TreeFeller.saplingFor(level.getBlockState(tree.root()));
		for (BlockPos pos : TreeFeller.collectFelling(level, tree)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.playSound(null, tree.root(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8F, 0.9F);
		if (LVConfig.get().replantSaplings) {
			village.getPendingSaplings().add(new VillageRecord.PendingSapling(sapling, tree.root()));
		}
	}

	private static void holdBlock(Villager mason, VillageRecord village, WallRing ring) {
		if (!LVConfig.get().showBuilderHeldItem) {
			return;
		}
		WallShapes.Blocks4 blocks = WallShapes.blocks(village);
		ItemStack item = new ItemStack(ring.getType() == WallRing.Type.PALISADE ? blocks.palisade() : blocks.main());
		if (!mason.getMainHandItem().is(item.getItem())) {
			mason.setItemSlot(EquipmentSlot.MAINHAND, item);
			mason.setDropChance(EquipmentSlot.MAINHAND, 0.0F); // never drop free blocks
		}
	}

	private static void release(Villager mason) {
		if (LVConfig.get().showBuilderHeldItem && !mason.isTrading()) {
			mason.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		mason.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
	}

	private static void releaseAll(ServerLevel level, Site site) {
		for (UUID uuid : site.workers.keySet()) {
			if (level.getEntity(uuid) instanceof Villager villager) {
				release(villager);
			}
		}
		site.workers.clear();
	}

	// ---------------------------------------------------------------- repairs and taking down

	/**
	 * Checks a few remembered wall blocks: one that is gone (air, fire, plants) without a player breaking it was lost
	 * to an explosion, fire or another mod, and its unit is built again (a completed ring is not announced again).
	 */
	private static void checkRepairs(ServerLevel level, VillageRegistry registry, VillageWalls walls, Site site) {
		if (walls.getBlocks().isEmpty()) {
			return;
		}
		if (site.repairCursor >= site.repairKeys.length) {
			site.repairKeys = walls.getBlocks().keySet().toLongArray();
			site.repairCursor = 0;
		}
		for (int i = 0; i < REPAIR_BATCH && site.repairCursor < site.repairKeys.length; i++) {
			long key = site.repairKeys[site.repairCursor++];
			if (!walls.getBlocks().containsKey(key)) {
				continue;
			}
			BlockPos pos = BlockPos.of(key);
			if (!level.isLoaded(pos) || !SiteFinder.isClearable(level.getBlockState(pos))) {
				continue;
			}
			int value = walls.getBlocks().remove(key);
			WallRing ring = walls.ring(VillageWalls.ringOf(value));
			if (ring != null && ring != walls.getRetiring() && VillageWalls.columnOf(value) < ring.units()) {
				ring.reopen(VillageWalls.columnOf(value));
			}
			registry.setDirty();
			LivingVillages.debug("Wall block at {} is gone, to be rebuilt", pos);
		}
	}

	/**
	 * Takes the old ring down, one block at a time, once the new one stands: an outgrown palisade, or what is left of
	 * the palisade a city wall replaced. Only the mod's own fences.
	 */
	private static void takeDown(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls, Site site) {
		WallRing old = walls.getRetiring();
		if (site.takeDown == null) {
			site.takeDown = new LongArrayList();
			walls.getBlocks().long2IntEntrySet().forEach(entry -> {
				if (VillageWalls.ringOf(entry.getIntValue()) == old.getId()) {
					site.takeDown.add(entry.getLongKey());
				}
			});
		}
		while (!site.takeDown.isEmpty()) {
			long key = site.takeDown.removeLong(site.takeDown.size() - 1);
			if (!walls.getBlocks().containsKey(key) || VillageWalls.ringOf(walls.getBlocks().get(key)) != old.getId()) {
				continue; // reused by the new ring, or broken meanwhile
			}
			BlockPos pos = BlockPos.of(key);
			if (!level.isLoaded(pos)) {
				site.takeDown.add(key);
				return; // later, when loaded
			}
			walls.getBlocks().remove(key);
			if (level.getBlockState(pos).is(BlockTags.FENCES)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			registry.setDirty();
			return;
		}
		site.takeDown = null;
		walls.setRetiring(null);
		registry.setDirty();
		WallRing ring = walls.outer();
		if (ring != null && ring.getType() == WallRing.Type.PALISADE) {
			Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_moved", ChronicleEntry.keyArg(ring.getType().langKey()));
		}
		LivingVillages.debug("Village {}: old ring taken down", village.getId());
	}

	/** Forgets runtime state (server stop). */
	public static void clear() {
		SITES.clear();
	}
}
