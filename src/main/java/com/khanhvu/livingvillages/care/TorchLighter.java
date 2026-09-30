package com.khanhvu.livingvillages.care;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.wall.VillageWalls;
import com.khanhvu.livingvillages.wall.WallBuilder;
import com.khanhvu.livingvillages.wall.WallRing;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Torches in dark spots of the village (spec v3-GĐ 5): every torchIntervalTicks a few random outdoor spots are
 * looked at, inside the city walls first. Only spots no building will ever need get one: right beside a road, on
 * the square around the bell, or along the inside of the wall. A spot whose block light is at most
 * torchLightLevel, on natural ground or on the mod's own wall, not on a road or a field, and at least torchSpacing
 * from the mod's other torches gets a vanilla torch; a villager nearby swings their arm. Torches are remembered
 * with the wall blocks, so one a player breaks is never placed again.
 */
public final class TorchLighter {
	/** Wall-block entries with this ring id are torches. */
	public static final int TORCH_RING = 0;
	private static final int SAMPLES = 16;
	private static final int INSIDE_TRIES = 8;
	private static final double SWING_RANGE = 8.0;

	private TorchLighter() {
	}

	public static boolean enabled() {
		return LVConfig.get().torchesEnabled && !WallBuilder.deferred();
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		if (!enabled() || Math.floorMod(level.getGameTime() + village.getId().hashCode(), config.torchIntervalTicks) != 0) {
			return;
		}
		RandomSource random = level.getRandom();
		WallRing ring = village.getWalls().outer();
		for (int i = 0; i < SAMPLES; i++) {
			BlockPos spot = ring != null && random.nextInt(10) < 7 ? insideWalls(ring, random) : nearBell(village, random);
			if (spot != null && tryLight(level, village, spot.getX(), spot.getZ())) {
				VillageRegistry.get(level).setDirty();
				return; // one torch at a time
			}
		}
	}

	/** Number of torches the mod put up for the village. */
	public static int count(VillageRecord village) {
		int count = 0;
		for (int value : village.getWalls().getBlocks().values()) {
			if (VillageWalls.ringOf(value) == TORCH_RING) {
				count++;
			}
		}
		return count;
	}

	private static BlockPos insideWalls(WallRing ring, RandomSource random) {
		int[] polygon = ring.getPolygon();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < polygon.length; i += 2) {
			minX = Math.min(minX, polygon[i]);
			maxX = Math.max(maxX, polygon[i]);
			minZ = Math.min(minZ, polygon[i + 1]);
			maxZ = Math.max(maxZ, polygon[i + 1]);
		}
		for (int t = 0; t < INSIDE_TRIES; t++) {
			int x = minX + random.nextInt(maxX - minX + 1);
			int z = minZ + random.nextInt(maxZ - minZ + 1);
			if (ring.contains(x + 0.5, z + 0.5)) {
				return new BlockPos(x, 0, z);
			}
		}
		return null;
	}

	private static BlockPos nearBell(VillageRecord village, RandomSource random) {
		int radius = VillageAnalyzer.areaRadius(village);
		BlockPos bell = village.getBellPos();
		return new BlockPos(bell.getX() + random.nextInt(radius * 2 + 1) - radius, 0, bell.getZ() + random.nextInt(radius * 2 + 1) - radius);
	}

	private static boolean tryLight(ServerLevel level, VillageRecord village, int x, int z) {
		LVConfig config = LVConfig.get();
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return false;
		}
		VillageWalls walls = village.getWalls();
		BlockPos ground = new BlockPos(x, SiteFinder.groundTop(level, x, z), z);
		BlockPos cell = ground.above();
		BlockState groundState = level.getBlockState(ground);
		boolean ownWall = walls.getBlocks().containsKey(ground.asLong()) && VillageWalls.ringOf(walls.getBlocks().get(ground.asLong())) != TORCH_RING;
		if (!(SiteFinder.isNaturalGround(groundState) || ownWall) || !groundState.getFluidState().isEmpty()
				|| RoadBuilder.roadBlocks().contains(groundState.getBlock()) || inField(village, x, z)
				|| !(ownWall || besideRoad(level, x, z) || onSquare(village, x, z, config) || alongWall(village, x, z, config))) {
			return false;
		}
		BlockState cellState = level.getBlockState(cell);
		BlockState torch = Blocks.TORCH.defaultBlockState();
		if (!SiteFinder.isClearable(cellState) || !level.canSeeSky(cell) || walls.getAbandoned().contains(cell.asLong())
				|| !groundState.isFaceSturdy(level, ground, Direction.UP) || !torch.canSurvive(level, cell)
				|| level.getBrightness(LightLayer.BLOCK, cell) > config.torchLightLevel || nearTorch(walls, cell, config.torchSpacing)) {
			return false;
		}
		level.setBlock(cell, torch, Block.UPDATE_ALL);
		walls.addBlock(cell.asLong(), TORCH_RING, 0);
		level.playSound(null, cell, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(cell).inflate(SWING_RANGE), v -> v.isAlive() && !v.isSleeping());
		if (!nearby.isEmpty()) {
			nearby.get(0).swing(InteractionHand.MAIN_HAND);
		}
		LivingVillages.debug("Village {}: torch at {}", village.getId(), cell);
		return true;
	}

	private static boolean besideRoad(ServerLevel level, int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					BlockPos pos = new BlockPos(x + dx, SiteFinder.groundTop(level, x + dx, z + dz), z + dz);
					if (RoadBuilder.roadBlocks().contains(level.getBlockState(pos).getBlock())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean onSquare(VillageRecord village, int x, int z, LVConfig config) {
		return village.horizontalDistSqr(new BlockPos(x, 0, z)) <= (double) config.plazaRadius * config.plazaRadius;
	}

	/** Inside the outermost wall, within the walkway kept free along it. */
	private static boolean alongWall(VillageRecord village, int x, int z, LVConfig config) {
		WallRing ring = village.getWalls().outer();
		if (ring == null || !ring.contains(x + 0.5, z + 0.5)) {
			return false;
		}
		int reach = config.wallInnerBuffer;
		for (int i = 0; i < ring.size(); i++) {
			if (Math.abs(ring.x(i) - x) <= reach && Math.abs(ring.z(i) - z) <= reach) {
				return true;
			}
		}
		return false;
	}

	private static boolean inField(VillageRecord village, int x, int z) {
		for (BoundingBox field : village.getFieldPlots()) {
			if (field.isInside(x, field.minY(), z)) {
				return true;
			}
		}
		return false;
	}

	private static boolean nearTorch(VillageWalls walls, BlockPos cell, int spacing) {
		for (Long2IntMap.Entry entry : walls.getBlocks().long2IntEntrySet()) {
			if (VillageWalls.ringOf(entry.getIntValue()) == TORCH_RING && BlockPos.of(entry.getLongKey()).distManhattan(cell) < spacing) {
				return true;
			}
		}
		return false;
	}
}
