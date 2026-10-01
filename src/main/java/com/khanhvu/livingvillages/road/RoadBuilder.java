package com.khanhvu.livingvillages.road;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.village.VillageType;
import com.khanhvu.livingvillages.wall.CitySites;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Village roads (spec v2-GĐ 10): a finished building is linked from its door to the nearest village road (any
 * {@code roadBlocks} block, which includes Regrowth's roads) within {@code roadSearchRadius}, or to the bell. The way
 * is found with A* over the ground (one block up or down per step, no water, lava, buildings or plots; roads may be
 * walked on), a few hundred nodes per tick. A mason then walks along it and turns natural ground into road, one
 * block at a time; without a mason it happens at half speed. Only grass, dirt, coarse dirt and podzol become dirt
 * path (sand becomes smooth sandstone in deserts, as in desert village streets); other blocks are left as they are.
 * Road work is not saved: a restart drops an unfinished road.
 */
public final class RoadBuilder {
	private static final int NODES_PER_TICK = 250;
	private static final int TICKS_PER_BLOCK = 20;
	private static final double MASON_REACH = 3.0;
	private static final int BELL_GOAL_DISTANCE = 3;
	private static final int MAX_VERTICAL_SEARCH = 8;
	private static final double ROAD_STEP_COST = 0.4;
	private static final double CLIMB_COST = 0.5;

	/** Where a road starts, and the building it leaves (walkable, although it is a plot). */
	private record Start(BlockPos pos, BoundingBox building) {
	}

	private static final class Job {
		final Deque<Start> starts = new ArrayDeque<>();
		@Nullable
		Search search;
		/** Ground blocks to turn into road, from the building outwards. */
		final List<BlockPos> path = new ArrayList<>();
		int index;
		double points;
		@Nullable
		UUID mason;
		long masonSince;
		/** The planned segment this path came from, so its saved progress follows the work; -1 for an A* path. */
		int segment = -1;
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();

	private RoadBuilder() {
	}

	public static void register() {
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			if (!LVConfig.get().buildRoads) {
				return;
			}
			BlockPos start = findStart(e.level(), e.footprint(), e.village().getBellPos());
			if (start != null) {
				JOBS.computeIfAbsent(e.village().getId(), id -> new Job()).starts.add(new Start(start, e.footprint()));
				LivingVillages.debug("Village {}: road planned from {}", e.village().getId(), start);
			}
		});
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		if (!LVConfig.get().buildRoads) {
			JOBS.remove(village.getId());
			return;
		}
		// The four axes are laid out as soon as the village has a frame, and are what the masons pave first.
		if (VillageRoads.layOutAxes(village)) {
			VillageRegistry.get(level).setDirty();
			LivingVillages.debug("Village {}: four road axes laid out from {}", village.getId(), VillageRoads.centre(village));
		}
		Job job = JOBS.get(village.getId());
		if (job == null) {
			if (village.getRoads().unfinished() < 0) {
				return;
			}
			job = JOBS.computeIfAbsent(village.getId(), id -> new Job());
		}
		if (job.index < job.path.size()) {
			work(level, village, job);
			return;
		}
		if (job.segment >= 0) {
			job.segment = -1; // the segment is finished; look for the next piece of work
		}
		if (job.search == null && takeSegment(level, village, job)) {
			work(level, village, job);
			return;
		}
		if (job.search == null) {
			Start start = job.starts.poll();
			if (start == null) {
				JOBS.remove(village.getId());
				return;
			}
			job.path.clear();
			job.index = 0;
			job.search = new Search(level, village, start.pos(), start.building());
		}
		Search search = job.search;
		Boolean found = search.step(level, NODES_PER_TICK);
		if (found == null) {
			return; // still searching
		}
		job.search = null;
		if (found) {
			job.path.addAll(search.result());
			LivingVillages.debug("Village {}: road of {} blocks from {} ({} nodes)", village.getId(), job.path.size(), search.start, search.expanded);
		} else {
			LivingVillages.debug("Village {}: no road found from {} ({} nodes)", village.getId(), search.start, search.expanded);
		}
	}

	/**
	 * Loads the columns still to work of the first unfinished planned road into the job. The segment's saved progress
	 * is what decides where to carry on, so a restart picks the road up where the masons left it. False when every
	 * planned road is done or the line is not loaded yet.
	 */
	private static boolean takeSegment(ServerLevel level, VillageRecord village, Job job) {
		VillageRoads roads = village.getRoads();
		int index = roads.unfinished();
		if (index < 0) {
			return false;
		}
		RoadSegment segment = roads.getSegments().get(index);
		job.path.clear();
		job.index = 0;
		for (int i = roads.progress(index); i < segment.length(); i++) {
			BlockPos cell = segment.cell(i);
			if (!level.hasChunk(cell.getX() >> 4, cell.getZ() >> 4)) {
				break; // the rest of the line is not loaded: pave what is here and come back for the rest
			}
			job.path.add(new BlockPos(cell.getX(), SiteFinder.groundTop(level, cell.getX(), cell.getZ()), cell.getZ()));
		}
		if (job.path.isEmpty()) {
			return false;
		}
		job.segment = index;
		LivingVillages.debug("Village {}: paving road {} ({}), {} of {} columns left", village.getId(), index,
				segment.axis() ? "axis" : "branch", job.path.size(), segment.length());
		return true;
	}

	// ---------------------------------------------------------------- building the road

	private static void work(ServerLevel level, VillageRecord village, Job job) {
		LVConfig config = LVConfig.get();
		long timeOfDay = level.getDayTime() % 24000L;
		if (timeOfDay < config.workStartTime || timeOfDay >= config.workEndTime) {
			return;
		}
		BlockPos cell = job.path.get(job.index);
		Villager mason = mason(level, village, job);
		if (mason != null) {
			BuilderAssignment.walkTo(mason, cell.above(), cell);
			double dx = mason.getX() - cell.getX() - 0.5;
			double dz = mason.getZ() - cell.getZ() - 0.5;
			if (dx * dx + dz * dz > MASON_REACH * MASON_REACH) {
				if (level.getGameTime() - job.masonSince > config.workTimeoutTicks) {
					job.mason = null; // cannot get there: the road goes on without them
				}
				return;
			}
			job.masonSince = level.getGameTime();
		}
		job.points += mason != null ? 1.0 / TICKS_PER_BLOCK : 0.5 / TICKS_PER_BLOCK;
		if (job.points < 1.0) {
			return;
		}
		job.points = 0;
		paveWidth(level, village, job, cell);
		job.index++;
		if (job.segment >= 0) {
			// A column an obstacle blocked counts as worked too, so a planned road never stalls on it.
			village.getRoads().advance(job.segment);
			VillageRegistry.get(level).setDirty();
		}
		if (job.index >= job.path.size()) {
			LivingVillages.debug("Village {}: road finished ({} blocks, mason {})", village.getId(), job.path.size(), mason != null);
			if (mason != null) {
				mason.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			}
		}
	}

	/**
	 * Paves the centre cell and, for a wider road, the columns beside it perpendicular to the travel direction.
	 * Each side column is paved at its own ground height and only when level with the centre (≤1 block difference).
	 */
	private static void paveWidth(ServerLevel level, VillageRecord village, Job job, BlockPos cell) {
		pave(level, village, cell);
		int half = (LVConfig.get().roadWidth - 1) / 2;
		if (half < 1) {
			return;
		}
		// Determine the neighbour (previous cell, or next cell at the start) to derive travel direction.
		BlockPos neighbour = job.index > 0 ? job.path.get(job.index - 1)
				: job.path.size() > 1 ? job.path.get(1) : null;
		if (neighbour == null) {
			return;
		}
		// A* only steps cardinally, so perpendicular = rotate 90°: (dx,dz) → (dz,-dx).
		// Both sides are paved, so the sign of the direction doesn't matter.
		int px = Integer.signum(cell.getZ() - neighbour.getZ());
		int pz = -Integer.signum(cell.getX() - neighbour.getX());
		for (int d = 1; d <= half; d++) {
			paveSide(level, village, cell, px * d, pz * d);
			paveSide(level, village, cell, -px * d, -pz * d);
		}
	}

	/** Paves one column beside the road at its own ground height; skipped when uneven or inside a plot. */
	private static void paveSide(ServerLevel level, VillageRecord village, BlockPos cell, int dx, int dz) {
		int x = cell.getX() + dx;
		int z = cell.getZ() + dz;
		if (!level.hasChunk(x >> 4, z >> 4) || insidePlot(village, x, z)) {
			return;
		}
		int groundY = SiteFinder.groundTop(level, x, z);
		if (Math.abs(groundY - cell.getY()) > 1) {
			return;
		}
		pave(level, village, new BlockPos(x, groundY, z));
	}

	/** Whether the column is inside a plot or the running project of the village. */
	static boolean insidePlot(VillageRecord village, int x, int z) {
		for (BoundingBox plot : village.getPlots()) {
			if (plot.isInside(x, plot.minY(), z)) {
				return true;
			}
		}
		BuildProject project = village.getProject();
		return project != null && project.getFootprint().isInside(x, project.getFootprint().minY(), z);
	}

	/** Turns one natural ground block into road and clears plants above it. */
	private static void pave(ServerLevel level, VillageRecord village, BlockPos ground) {
		if (!level.isLoaded(ground)) {
			return;
		}
		BlockState state = level.getBlockState(ground);
		Block road = roadFor(village, state);
		if (road == null) {
			return;
		}
		BlockPos above = ground.above();
		BlockState aboveState = level.getBlockState(above);
		if (!aboveState.isAir()) {
			if (!SiteFinder.isClearable(aboveState)) {
				return;
			}
			level.destroyBlock(above, false);
		}
		level.setBlockAndUpdate(ground, road.defaultBlockState());
	}

	/** The road block natural ground turns into (dirt path, smooth sandstone on desert sand), or null if it stays. */
	@Nullable
	public static Block roadFor(VillageRecord village, BlockState ground) {
		if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL)) {
			return Blocks.DIRT_PATH;
		}
		if (VillageTicker.villageType(village) == VillageType.DESERT && (ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND))) {
			return Blocks.SMOOTH_SANDSTONE;
		}
		return null;
	}

	/** The job's mason, or the nearest free mason of the village; null without one. */
	@Nullable
	private static Villager mason(ServerLevel level, VillageRecord village, Job job) {
		if (job.mason != null && level.getEntity(job.mason) instanceof Villager villager && villager.isAlive()
				&& villager.getVillagerData().getProfession() == VillagerProfession.MASON) {
			return villager;
		}
		BuildProject project = village.getProject();
		UUID builder = project == null ? null : project.getBuilderUuid();
		BlockPos cell = job.path.get(job.index);
		Villager best = null;
		for (Villager villager : VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village))) {
			if (villager.getVillagerData().getProfession() == VillagerProfession.MASON && !villager.getUUID().equals(builder)
					&& !villager.isSleeping() && !villager.isTrading()
					&& (best == null || villager.distanceToSqr(cell.getCenter()) < best.distanceToSqr(cell.getCenter()))) {
				best = villager;
			}
		}
		if (best != null && !best.getUUID().equals(job.mason)) {
			job.mason = best.getUUID();
			job.masonSince = level.getGameTime();
		}
		return best;
	}

	// ---------------------------------------------------------------- where the road starts

	/**
	 * The walking position in front of the building's door (or fence gate): the side of the door that is open to the
	 * sky. Without one, the ground just outside the footprint edge nearest to the bell.
	 */
	@Nullable
	private static BlockPos findStart(ServerLevel level, BoundingBox footprint, BlockPos bell) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
				for (int y = footprint.minY(); y <= footprint.maxY(); y++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					boolean door = state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
					if (!door && !(state.getBlock() instanceof FenceGateBlock)) {
						continue;
					}
					Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
					for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
						BlockPos outside = pos.relative(side).immutable();
						if (level.canSeeSky(outside) && SiteFinder.isClearable(level.getBlockState(outside))) {
							double dist = outside.distSqr(bell);
							if (dist < bestDist) {
								bestDist = dist;
								best = outside;
							}
						}
					}
				}
			}
		}
		if (best != null) {
			return best;
		}
		int x = Math.clamp(bell.getX(), footprint.minX() - 1, footprint.maxX() + 1);
		int z = Math.clamp(bell.getZ(), footprint.minZ() - 1, footprint.maxZ() + 1);
		if (footprint.isInside(x, footprint.minY(), z)) {
			return null; // the bell is inside the footprint: nothing sensible to do
		}
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		return new BlockPos(x, SiteFinder.groundTop(level, x, z) + 1, z);
	}

	// ---------------------------------------------------------------- A*

	/** A* from the start to any road block (or to the bell), spread over several ticks. */
	private static final class Search {
		final BlockPos start;
		final BoundingBox building;
		final VillageRecord village;
		final Set<Block> roadBlocks;
		@Nullable
		final BlockPos target;
		final boolean toBell;
		/** A building outside the city wall is linked to the nearest gate, the way into the city (spec v3-GĐ 3). */
		@Nullable
		final BlockPos gate;
		final PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
		final Map<Long, Long> cameFrom = new HashMap<>();
		final Map<Long, Double> cost = new HashMap<>();
		final Set<Long> closed = new HashSet<>();
		int expanded;
		long goal = Long.MIN_VALUE;

		Search(ServerLevel level, VillageRecord village, BlockPos start, BoundingBox building) {
			this.start = start;
			this.building = building;
			this.village = village;
			this.roadBlocks = roadBlocks();
			this.gate = CitySites.gateFor(village, start);
			this.target = gate != null ? gate : nearestRoad(level, start);
			this.toBell = target == null;
			cost.put(start.asLong(), 0.0);
			open.add(new long[] {start.asLong(), Double.doubleToLongBits(heuristic(start))});
		}

		/** Null while searching, then whether a way was found. */
		@Nullable
		Boolean step(ServerLevel level, int budget) {
			int maxNodes = LVConfig.get().roadMaxNodes;
			while (budget-- > 0) {
				long[] node = open.poll();
				if (node == null) {
					return false;
				}
				long current = node[0];
				if (!closed.add(current)) {
					continue;
				}
				BlockPos pos = BlockPos.of(current);
				if (isGoal(level, pos)) {
					goal = current;
					return true;
				}
				if (++expanded > maxNodes) {
					return false;
				}
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos next = walkable(level, pos, direction);
					if (next == null || closed.contains(next.asLong())) {
						continue;
					}
					boolean onRoad = roadBlocks.contains(level.getBlockState(next.below()).getBlock());
					double step = (onRoad ? ROAD_STEP_COST : 1.0) + (next.getY() != pos.getY() ? CLIMB_COST : 0.0);
					double newCost = cost.get(current) + step;
					Double known = cost.get(next.asLong());
					if (known == null || newCost < known) {
						cost.put(next.asLong(), newCost);
						cameFrom.put(next.asLong(), current);
						open.add(new long[] {next.asLong(), Double.doubleToLongBits(newCost + heuristic(next))});
					}
				}
			}
			return null;
		}

		/** Ground blocks from the start to the goal, the road block reached excluded. */
		List<BlockPos> result() {
			List<BlockPos> cells = new ArrayList<>();
			Long current = goal;
			while (current != null) {
				cells.add(BlockPos.of(current).below());
				current = cameFrom.get(current);
			}
			Collections.reverse(cells);
			if (!toBell && gate == null && !cells.isEmpty()) {
				cells.remove(cells.size() - 1); // already a road
			}
			return cells;
		}

		private boolean isGoal(ServerLevel level, BlockPos feet) {
			if (gate != null) {
				return Math.abs(feet.getX() - gate.getX()) + Math.abs(feet.getZ() - gate.getZ()) <= 1;
			}
			if (toBell) {
				BlockPos bell = village.getBellPos();
				return Math.abs(feet.getX() - bell.getX()) + Math.abs(feet.getZ() - bell.getZ()) <= BELL_GOAL_DISTANCE;
			}
			return !feet.equals(start) && roadBlocks.contains(level.getBlockState(feet.below()).getBlock());
		}

		private double heuristic(BlockPos pos) {
			BlockPos goalPos = toBell ? village.getBellPos() : target;
			// Roads are cheaper than a full step, so the estimate uses that cost to stay admissible.
			return (Math.abs(pos.getX() - goalPos.getX()) + Math.abs(pos.getZ() - goalPos.getZ())) * ROAD_STEP_COST;
		}

		/** The walking position one step away: natural or road ground, two free blocks above, at most one block up or down. */
		@Nullable
		private BlockPos walkable(ServerLevel level, BlockPos from, Direction direction) {
			int x = from.getX() + direction.getStepX();
			int z = from.getZ() + direction.getStepZ();
			boolean ownBuilding = building.isInside(x, building.minY(), z);
			if (!level.hasChunk(x >> 4, z >> 4) || (!ownBuilding && insidePlot(x, z))) {
				return null;
			}
			for (int dy : new int[] {0, 1, -1}) {
				BlockPos feet = new BlockPos(x, from.getY() + dy, z);
				BlockState ground = level.getBlockState(feet.below());
				// Around its own door the road may cross the building's porch, steps or garden.
				boolean groundOk = (SiteFinder.isNaturalGround(ground) || roadBlocks.contains(ground.getBlock())
						|| (ownBuilding && ground.isFaceSturdy(level, feet.below(), Direction.UP)))
						&& ground.getFluidState().isEmpty();
				if (!groundOk || !SiteFinder.isClearable(level.getBlockState(feet)) || !SiteFinder.isClearable(level.getBlockState(feet.above()))) {
					continue;
				}
				if (dy == 1 && !SiteFinder.isClearable(level.getBlockState(from.above(2)))) {
					continue; // no head room to step up
				}
				return feet;
			}
			return null;
		}

		private boolean insidePlot(int x, int z) {
			return RoadBuilder.insidePlot(village, x, z);
		}

		/** The nearest road block with open air above within roadSearchRadius, outside every plot; null if none. */
		@Nullable
		private BlockPos nearestRoad(ServerLevel level, BlockPos from) {
			int radius = LVConfig.get().roadSearchRadius;
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			for (int x = from.getX() - radius; x <= from.getX() + radius; x++) {
				for (int z = from.getZ() - radius; z <= from.getZ() + radius; z++) {
					if (!level.hasChunk(x >> 4, z >> 4) || insidePlot(x, z)) {
						continue;
					}
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					if (Math.abs(y - from.getY()) > MAX_VERTICAL_SEARCH) {
						continue;
					}
					BlockPos ground = new BlockPos(x, y, z);
					if (roadBlocks.contains(level.getBlockState(ground).getBlock())) {
						double dist = ground.distSqr(from);
						if (dist < bestDist) {
							bestDist = dist;
							best = ground;
						}
					}
				}
			}
			return best;
		}
	}

	/** Whether the villager is paving a road now (masons also build walls). */
	public static boolean isPaving(UUID villager) {
		for (Job job : JOBS.values()) {
			if (villager.equals(job.mason) && job.index < job.path.size()) {
				return true;
			}
		}
		return false;
	}

	/** The {@code roadBlocks} of the config as blocks. */
	public static Set<Block> roadBlocks() {
		Set<Block> blocks = new HashSet<>();
		for (String id : LVConfig.get().roadBlocks) {
			ResourceLocation location = ResourceLocation.tryParse(id);
			if (location != null) {
				BuiltInRegistries.BLOCK.getOptional(location).ifPresent(blocks::add);
			}
		}
		return blocks;
	}

	/** Drops every road in progress (server stop). */
	public static void clear() {
		JOBS.clear();
	}
}
