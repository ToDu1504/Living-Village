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
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds and keeps the walls of villages near players (spec v3-GĐ 5.2–5.3). Step 5A: a palisade from level Village,
 * with a gate wherever a road crosses it, moved outwards when the village grows past it (new palisade first, then
 * the old one taken down). Masons build it, each at {@code wallBlocksPerSecond}; without a mason the village builds
 * at half that rate. Every block placed is remembered: one a player breaks stays open, one lost otherwise
 * (explosion, fire) is built again.
 */
public final class WallBuilder {
	private static final String REGROWTH = "regrowth";
	private static final int REPAIR_INTERVAL = 20;
	private static final int REPAIR_BATCH = 32;
	private static final int TAKE_DOWN_INTERVAL = 5;
	private static final double REACH = 4.0;
	/** A mason picks the nearest open column among this many next in the building order. */
	private static final int CLAIM_WINDOW = 12;
	private static final double MAX_POINTS = 2.0;

	/** What working on one column did. */
	private enum Result {
		/** A block was placed or a tree felled: costs one point. */
		WORKED,
		/** The column is settled without work (already built, left open). */
		SETTLED,
		/** Not loaded: try again later. */
		WAIT
	}

	private static final class Worker {
		int column = -1;
		long since;
		double points;
	}

	/** Runtime state of one village's walls; not saved (claims are simply made again after a restart). */
	private static final class Site {
		final Map<UUID, Worker> workers = new HashMap<>();
		/** Columns masons could not reach: built without anyone, at the village rate. */
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

	/** Walls are built unless switched off, or left to Regrowth when it is installed (spec v3 §1.4). */
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

	public static void register() {
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			WallRing outer = e.village().getWalls().outer();
			if (outer != null && outer.getType() == WallRing.Type.PALISADE && !VillageBoundary.containsBox(outer.getPolygon(), e.footprint())) {
				e.village().getWalls().setMoveWanted(true);
				VillageRegistry.get(e.level()).setDirty();
			}
		});
		// A wall block a player breaks is theirs to leave open (spec 5.3).
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
					if (ring != null && ring != walls.getRetiring()) {
						ring.setStatus(VillageWalls.columnOf(value), WallRing.WEAK);
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
		if (ring.count(WallRing.PENDING) > 0) {
			if (VillageTicker.isWorkTime(level) && level.getRaidAt(village.getBellPos()) == null) {
				build(level, registry, village, walls, ring, site);
			}
			return;
		}
		releaseAll(level, site);
		if (!ring.isCompleted()) {
			ring.setCompleted(true);
			registry.setDirty();
			if (walls.getRetiring() == null) {
				Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_finished", ChronicleEntry.keyArg(ring.getType().langKey()));
			}
		}
		if (walls.getRetiring() != null && time % TAKE_DOWN_INTERVAL == 0 && VillageTicker.isWorkTime(level)) {
			takeDown(level, registry, village, walls, site);
		}
	}

	// ---------------------------------------------------------------- planning

	/** Starts the first palisade, or moves it when the village grew past it. */
	private static void plan(ServerLevel level, VillageRegistry registry, VillageRecord village, VillageWalls walls) {
		LVConfig config = LVConfig.get();
		if (!VillageLevel.enabled() || village.getLevel() < config.palisadeMinLevel || village.getVillageType() == null) {
			return;
		}
		WallRing outer = walls.outer();
		if (outer == null) {
			WallRing ring = createRing(level, village, walls, WallRing.Type.PALISADE, VillageBoundary.compute(level, village, plot -> true));
			if (ring != null) {
				walls.getRings().add(ring);
				registry.setDirty();
				Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_started", ChronicleEntry.keyArg(ring.getType().langKey()));
				LivingVillages.debug("Village {}: palisade planned, {} columns, {} gate(s)", village.getId(), ring.size(), ring.runs(WallRing.GATE));
			}
			return;
		}
		// 5.2a: the palisade follows the village, at most once per palisadeMoveCooldown, never while one is going up or down.
		if (outer.getType() != WallRing.Type.PALISADE || !walls.isMoveWanted() || outer.count(WallRing.PENDING) > 0
				|| walls.getRetiring() != null || level.getGameTime() - outer.getCreatedTick() < config.palisadeMoveCooldown) {
			return;
		}
		boolean outside = village.getPlots().stream().anyMatch(plot -> !VillageBoundary.containsBox(outer.getPolygon(), plot));
		if (!outside) {
			walls.setMoveWanted(false);
			registry.setDirty();
			return;
		}
		WallRing ring = createRing(level, village, walls, WallRing.Type.PALISADE, VillageBoundary.compute(level, village, plot -> true));
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
	 * A new ring along the polygon, with gates where roads cross it (or one gate nearest the bell when none does).
	 * Null while part of the line is in an unloaded chunk.
	 */
	@Nullable
	private static WallRing createRing(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing.Type type, int[] polygon) {
		WallRing ring = new WallRing(walls.takeRingId(), type, polygon, level.getGameTime());
		int n = ring.size();
		for (int i = 0; i < n; i++) {
			if (!level.hasChunk(ring.x(i) >> 4, ring.z(i) >> 4)) {
				return null;
			}
		}
		Set<Block> roads = RoadBuilder.roadBlocks();
		boolean[] road = new boolean[n];
		boolean any = false;
		for (int i = 0; i < n; i++) {
			int x = ring.x(i);
			int z = ring.z(i);
			road[i] = roads.contains(level.getBlockState(new BlockPos(x, SiteFinder.groundTop(level, x, z), z)).getBlock());
			any |= road[i];
		}
		int half = LVConfig.get().gateWidth / 2;
		if (any) {
			for (int i = 0; i < n; i++) {
				if (road[i]) {
					markGate(ring, i, half);
				}
			}
		} else {
			BlockPos bell = village.getBellPos();
			int nearest = 0;
			long best = Long.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				long dx = ring.x(i) - bell.getX();
				long dz = ring.z(i) - bell.getZ();
				if (dx * dx + dz * dz < best) {
					best = dx * dx + dz * dz;
					nearest = i;
				}
			}
			markGate(ring, nearest, half);
		}
		return ring;
	}

	/** Opens a gate of gateWidth columns centred on {@code column}. */
	private static void markGate(WallRing ring, int column, int half) {
		int n = ring.size();
		for (int d = -half; d <= half; d++) {
			ring.setStatus(Math.floorMod(column + d, n), WallRing.GATE);
		}
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
			if (worker.column < 0 || ring.status(worker.column) != WallRing.PENDING) {
				worker.column = claim(ring, site, mason);
				worker.since = level.getGameTime();
				if (worker.column < 0) {
					release(mason);
					continue;
				}
				LivingVillages.debug("Village {}: mason {} takes wall column {}", village.getId(), mason.getUUID(), worker.column);
			}
			int x = ring.x(worker.column);
			int z = ring.z(worker.column);
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			BlockPos target = new BlockPos(x, SiteFinder.groundTop(level, x, z) + 1, z);
			BuilderAssignment.walkTo(mason, target, target);
			double dx = mason.getX() - x - 0.5;
			double dz = mason.getZ() - z - 0.5;
			if (dx * dx + dz * dz > REACH * REACH) {
				if (level.getGameTime() - worker.since > config.workTimeoutTicks) {
					site.remote.add(worker.column); // cannot get there: built without them
					worker.column = -1;
				}
				continue;
			}
			worker.since = level.getGameTime();
			worker.points = Math.min(MAX_POINTS, worker.points + rate);
			while (worker.points >= 1.0 && ring.status(worker.column) == WallRing.PENDING && VillageTicker.hasBudget(level)) {
				Result result = work(level, village, walls, ring, worker.column);
				if (result == Result.WAIT) {
					break;
				}
				changed = true;
				if (result == Result.WORKED) {
					VillageTicker.useBudget(level);
					worker.points -= 1.0;
					mason.swing(InteractionHand.MAIN_HAND);
					holdBlock(mason, village);
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
		// Without masons, and for columns no mason can reach, the village builds at half the rate.
		if (masons.isEmpty() || !site.remote.isEmpty()) {
			site.remotePoints = Math.min(MAX_POINTS, site.remotePoints + rate / 2.0);
			while (site.remotePoints >= 1.0 && VillageTicker.hasBudget(level)) {
				int column = remoteColumn(ring, site, masons.isEmpty());
				if (column < 0) {
					break;
				}
				Result result = work(level, village, walls, ring, column);
				if (result == Result.WAIT) {
					site.remote.remove(column);
					break;
				}
				changed = true;
				if (ring.status(column) != WallRing.PENDING) {
					site.remote.remove(column);
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

	/** Adult masons free for the walls: not building a house, not paving, not the leader, awake and not trading. */
	private static List<Villager> masons(ServerLevel level, VillageRecord village) {
		BuildProject project = village.getProject();
		UUID builder = project == null ? null : project.getBuilderUuid();
		return VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village)).stream()
				.filter(v -> v.getVillagerData().getProfession() == VillagerProfession.MASON && !v.isSleeping() && !v.isTrading()
						&& !v.getUUID().equals(builder) && !v.getUUID().equals(village.getLeaderUuid()) && !RoadBuilder.isPaving(v.getUUID()))
				.toList();
	}

	/** The nearest open column, among the next few in building order, that no other mason works on; -1 if none. */
	private static int claim(WallRing ring, Site site, Villager mason) {
		Set<Integer> taken = new HashSet<>();
		for (Worker other : site.workers.values()) {
			taken.add(other.column);
		}
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		int seen = 0;
		for (int column : ring.order()) {
			if (ring.status(column) != WallRing.PENDING || taken.contains(column) || site.remote.contains(column)) {
				continue;
			}
			double dx = mason.getX() - ring.x(column) - 0.5;
			double dz = mason.getZ() - ring.z(column) - 0.5;
			if (dx * dx + dz * dz < bestDist) {
				bestDist = dx * dx + dz * dz;
				best = column;
			}
			if (++seen >= CLAIM_WINDOW) {
				break;
			}
		}
		return best;
	}

	/** The next column built without a mason: unreachable ones first, then (with no mason at all) the next open one. */
	private static int remoteColumn(WallRing ring, Site site, boolean noMasons) {
		while (!site.remote.isEmpty()) {
			int column = site.remote.firstInt();
			if (ring.status(column) == WallRing.PENDING) {
				return column;
			}
			site.remote.remove(column);
		}
		if (noMasons) {
			for (int column : ring.order()) {
				if (ring.status(column) == WallRing.PENDING) {
					return column;
				}
			}
		}
		return -1;
	}

	/**
	 * Works on one column: fells a natural tree standing in it, or places the palisade block, or leaves it open
	 * (weak point) when it cannot hold a wall. Only air, plants and natural leaves are ever replaced.
	 */
	private static Result work(ServerLevel level, VillageRecord village, VillageWalls walls, WallRing ring, int column) {
		LVConfig config = LVConfig.get();
		int x = ring.x(column);
		int z = ring.z(column);
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return Result.WAIT;
		}
		int groundY = config.allowTreeClearing ? SiteFinder.groundTopThroughTrees(level, x, z) : SiteFinder.groundTop(level, x, z);
		BlockPos ground = new BlockPos(x, groundY, z);
		BlockPos cell = ground.above();
		long key = cell.asLong();
		if (walls.getBlocks().containsKey(key)) {
			walls.addBlock(key, ring.getId(), column); // already standing (an old palisade on the same line): now this ring's
			ring.setStatus(column, WallRing.DONE);
			return Result.SETTLED;
		}
		BlockState groundState = level.getBlockState(ground);
		BlockState cellState = level.getBlockState(cell);
		if (TreeFeller.isTreeLog(cellState) && config.allowTreeClearing) {
			TreeFeller.Tree tree = TreeFeller.findTree(level, cell, config.maxTreeLogs);
			if (tree != null) {
				fell(level, village, tree);
				return Result.WORKED; // the column is built next time
			}
		}
		if (RoadBuilder.roadBlocks().contains(groundState.getBlock())) {
			markGate(ring, column, config.gateWidth / 2); // a road made since the ring was planned
			return Result.SETTLED;
		}
		if (walls.getAbandoned().contains(key) || !groundState.getFluidState().isEmpty() || !SiteFinder.isNaturalGround(groundState)
				|| insidePlot(level, x, z) || !canReplace(cellState) || tooSteep(level, ring, column, groundY, config)) {
			ring.setStatus(column, WallRing.WEAK);
			return Result.SETTLED;
		}
		Block block = palisadeBlock(village);
		BlockState state = Block.updateFromNeighbourShapes(block.defaultBlockState(), level, cell);
		level.setBlock(cell, state, Block.UPDATE_ALL);
		walls.addBlock(key, ring.getId(), column);
		ring.setStatus(column, WallRing.DONE);
		SoundType sound = state.getSoundType();
		level.playSound(null, cell, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 4.0F, sound.getPitch() * 0.8F);
		return Result.WORKED;
	}

	/** Air, plants, snow layers, natural leaves and saplings (a replanted one on the line) may give way to the wall. */
	private static boolean canReplace(BlockState state) {
		return SiteFinder.isClearable(state) || TreeFeller.isNaturalLeaves(state) || state.is(BlockTags.SAPLINGS);
	}

	/** A cliff: the ground differs from the previous column by more than maxWallStep. */
	private static boolean tooSteep(ServerLevel level, WallRing ring, int column, int groundY, LVConfig config) {
		int previous = Math.floorMod(column - 1, ring.size());
		int px = ring.x(previous);
		int pz = ring.z(previous);
		if (!level.hasChunk(px >> 4, pz >> 4)) {
			return false;
		}
		int previousY = config.allowTreeClearing ? SiteFinder.groundTopThroughTrees(level, px, pz) : SiteFinder.groundTop(level, px, pz);
		return Math.abs(previousY - groundY) > config.maxWallStep;
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

	public static Block palisadeBlock(VillageRecord village) {
		LVConfig.WallBlockSet set = LVConfig.get().wallBlocks.get(VillageTicker.villageType(village).getSerializedName());
		ResourceLocation id = set == null ? null : ResourceLocation.tryParse(set.palisade);
		return id == null ? Blocks.OAK_FENCE : BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.OAK_FENCE);
	}

	private static void holdBlock(Villager mason, VillageRecord village) {
		if (!LVConfig.get().showBuilderHeldItem) {
			return;
		}
		ItemStack item = new ItemStack(palisadeBlock(village));
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
	 * to an explosion, fire or another mod, and its column is built again.
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
			if (ring != null && ring != walls.getRetiring()) {
				ring.setStatus(VillageWalls.columnOf(value), WallRing.PENDING); // a completed ring stays completed: no second announcement
			}
			registry.setDirty();
			LivingVillages.debug("Wall block at {} is gone, to be rebuilt", pos);
		}
	}

	/** Takes the old palisade down, one block at a time, once the new one stands (spec 5.2a). Only the mod's own blocks. */
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
				continue; // reused by the new palisade, or broken meanwhile
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
		Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.wall_moved",
				ChronicleEntry.keyArg(ring != null ? ring.getType().langKey() : old.getType().langKey()));
		LivingVillages.debug("Village {}: old palisade taken down", village.getId());
	}

	/** Forgets runtime state (server stop). */
	public static void clear() {
		SITES.clear();
	}
}
