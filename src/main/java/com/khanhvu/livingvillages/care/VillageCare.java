package com.khanhvu.livingvillages.care;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.wall.WallBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The village keeps itself tidy (spec v3-GĐ 6): every roadCareIntervalTicks a villager out and about looks at the
 * ground around them and fixes up to careBlocksPerRun things: fills a pothole in a road, raises a road block sunk
 * one below both its neighbours, sweeps snow off a road, cuts grass and wild flowers on a road or by a door. The
 * villager swings their arm; nobody around, nothing happens. Only plants that grow by themselves are cut: potted
 * flowers, saplings, crops and leaves are never touched.
 */
public final class VillageCare {
	private static final int LOOK_RADIUS = 6;
	private static final int SAMPLES = 16;
	private static final int DOOR_REACH = 2;

	/** Fixes since the server started, per village, for the status command (not saved). */
	private static final Map<UUID, int[]> COUNTS = new HashMap<>();

	private VillageCare() {
	}

	public static boolean enabled() {
		LVConfig config = LVConfig.get();
		return (config.roadCareEnabled || config.grassCuttingEnabled) && !WallBuilder.deferred();
	}

	/** Roads mended and plants cut since the server started. */
	public static int[] counts(VillageRecord village) {
		return COUNTS.getOrDefault(village.getId(), new int[2]);
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		if (!enabled() || Math.floorMod(level.getGameTime() + village.getId().hashCode() / 7, config.roadCareIntervalTicks) != 0) {
			return;
		}
		RandomSource random = level.getRandom();
		List<Villager> adults = VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village)).stream()
				.filter(v -> !v.isSleeping()).toList();
		if (adults.isEmpty()) {
			return;
		}
		Villager villager = adults.get(random.nextInt(adults.size()));
		BlockPos around = villager.blockPosition();
		int done = 0;
		for (int i = 0; i < SAMPLES && done < config.careBlocksPerRun; i++) {
			int x = around.getX() + random.nextInt(LOOK_RADIUS * 2 + 1) - LOOK_RADIUS;
			int z = around.getZ() + random.nextInt(LOOK_RADIUS * 2 + 1) - LOOK_RADIUS;
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			int kind = care(level, village, x, z, config);
			if (kind >= 0) {
				done++;
				COUNTS.computeIfAbsent(village.getId(), id -> new int[2])[kind]++;
			}
		}
		if (done > 0) {
			villager.swing(InteractionHand.MAIN_HAND);
		}
	}

	/** Fixes one thing in the column: 0 for road work, 1 for a plant cut, -1 when there was nothing to do. */
	private static int care(ServerLevel level, VillageRecord village, int x, int z, LVConfig config) {
		Set<Block> roads = RoadBuilder.roadBlocks();
		BlockPos ground = new BlockPos(x, SiteFinder.groundTop(level, x, z), z);
		BlockState groundState = level.getBlockState(ground);
		BlockPos above = ground.above();
		BlockState aboveState = level.getBlockState(above);
		boolean onRoad = roads.contains(groundState.getBlock());
		if (onRoad) {
			if (config.roadCareEnabled && (aboveState.is(Blocks.SNOW) || aboveState.is(Blocks.POWDER_SNOW))) {
				level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.playSound(null, above, SoundEvents.SNOW_BREAK, SoundSource.BLOCKS, 0.6F, 1.0F);
				return 0;
			}
			if (config.grassCuttingEnabled && isWildPlant(aboveState)) {
				cut(level, above);
				return 1;
			}
			if (config.roadCareEnabled && raiseSunkRoad(level, ground, groundState, roads)) {
				return 0;
			}
			return -1;
		}
		if (config.roadCareEnabled && SiteFinder.isNaturalGround(groundState) && RoadBuilder.roadFor(village, groundState) != null
				&& isPothole(level, ground, roads) && SiteFinder.isClearable(aboveState)) {
			if (!aboveState.isAir()) {
				level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			level.setBlock(ground, RoadBuilder.roadFor(village, groundState).defaultBlockState(), Block.UPDATE_ALL);
			LivingVillages.debug("Village {}: pothole filled at {}", village.getId(), ground);
			return 0;
		}
		if (config.grassCuttingEnabled && isWildPlant(aboveState) && nearDoor(level, above)) {
			cut(level, above);
			return 1;
		}
		return -1;
	}

	/** Grass, ferns and wild flowers: plants that grow by themselves. */
	private static boolean isWildPlant(BlockState state) {
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
				|| state.is(Blocks.DEAD_BUSH) || state.is(BlockTags.SMALL_FLOWERS) || state.is(BlockTags.TALL_FLOWERS);
	}

	/** Removes a plant without drops; the other half of a tall plant goes with it. */
	private static void cut(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.6F, 1.0F);
	}

	/** Natural ground between two road blocks in a line, or with roads on three sides, at the same height. */
	private static boolean isPothole(ServerLevel level, BlockPos ground, Set<Block> roads) {
		boolean[] road = new boolean[4];
		int count = 0;
		int i = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			road[i] = roads.contains(level.getBlockState(ground.relative(direction)).getBlock());
			count += road[i] ? 1 : 0;
			i++;
		}
		// Plane.HORIZONTAL is north, east, south, west: 0/2 and 1/3 are opposite sides.
		return count >= 3 || (road[0] && road[2]) || (road[1] && road[3]);
	}

	/**
	 * A road block one lower than the road on both sides in a line: the block above becomes road and the sunk one
	 * dirt, when the space above is free.
	 */
	private static boolean raiseSunkRoad(ServerLevel level, BlockPos ground, BlockState groundState, Set<Block> roads) {
		BlockPos up = ground.above();
		if (!level.getBlockState(up).isAir() || !level.getBlockState(up.above()).isAir()) {
			return false;
		}
		for (Direction direction : new Direction[] {Direction.NORTH, Direction.EAST}) {
			BlockState a = level.getBlockState(up.relative(direction));
			BlockState b = level.getBlockState(up.relative(direction.getOpposite()));
			if (roads.contains(a.getBlock()) && roads.contains(b.getBlock())) {
				level.setBlock(ground, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(up, groundState, Block.UPDATE_ALL);
				return true;
			}
		}
		return false;
	}

	private static boolean nearDoor(ServerLevel level, BlockPos pos) {
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-DOOR_REACH, -1, -DOOR_REACH), pos.offset(DOOR_REACH, 1, DOOR_REACH))) {
			if (level.getBlockState(near).getBlock() instanceof DoorBlock) {
				return true;
			}
		}
		return false;
	}

	/** Forgets the counts (server stop). */
	public static void clear() {
		COUNTS.clear();
	}
}
