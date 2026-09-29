package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Replants one sapling per tree felled for a building, 3–8 blocks from the new house. A farmer walks to the spot
 * and the sapling appears when they arrive; without a farmer (or if they cannot get there) it is planted directly.
 */
public final class Replanter {
	private static final int MIN_DISTANCE = 3;
	private static final int MAX_DISTANCE = 8;
	private static final int SPOT_ATTEMPTS = 16;
	private static final int REACH = 3;
	private static final int START_INTERVAL = 20;

	/** The sapling currently being planted per village. Not saved: after a restart a new spot is picked. */
	private static final Map<UUID, Task> TASKS = new HashMap<>();

	private record Task(VillageRecord.PendingSapling order, BlockPos spot, @Nullable UUID farmer, long startTick) {
	}

	private Replanter() {
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		if (village.getPendingSaplings().isEmpty()) {
			TASKS.remove(village.getId());
			return;
		}
		long now = level.getGameTime();
		Task task = TASKS.get(village.getId());
		if (task == null) {
			if (now % START_INTERVAL == 0) {
				start(level, village, now);
			}
			return;
		}
		Villager farmer = task.farmer() == null ? null : level.getEntity(task.farmer()) instanceof Villager v && v.isAlive() ? v : null;
		boolean arrived = farmer != null && farmer.blockPosition().closerThan(task.spot(), REACH);
		boolean timedOut = now - task.startTick() > LVConfig.get().workTimeoutTicks;
		if (farmer == null || arrived || timedOut) {
			plant(level, registry, village, task, farmer);
		} else {
			BuilderAssignment.walkTo(farmer, task.spot(), task.spot());
		}
	}

	private static void start(ServerLevel level, VillageRecord village, long now) {
		VillageRecord.PendingSapling order = village.getPendingSaplings().get(0);
		BlockPos spot = findSpot(level, village, order, level.getRandom());
		if (spot == null) {
			return; // try again later, maybe after chunks load or the ground changes
		}
		Villager farmer = chooseFarmer(level, village, spot);
		TASKS.put(village.getId(), new Task(order, spot, farmer == null ? null : farmer.getUUID(), now));
	}

	private static void plant(ServerLevel level, VillageRegistry registry, VillageRecord village, Task task, @Nullable Villager farmer) {
		TASKS.remove(village.getId());
		Block block = BuiltInRegistries.BLOCK.get(task.order().sapling());
		BlockState sapling = block.defaultBlockState();
		if (!isValidSpot(level, task.spot(), sapling)) {
			return; // the spot changed meanwhile; a new one is picked next time
		}
		level.setBlock(task.spot(), sapling, Block.UPDATE_ALL);
		village.getPendingSaplings().remove(task.order());
		registry.setDirty();
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, task.spot().getX() + 0.5, task.spot().getY() + 0.5, task.spot().getZ() + 0.5,
				6, 0.3, 0.3, 0.3, 0.0);
		level.playSound(null, task.spot(), SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
		if (farmer != null) {
			farmer.swing(InteractionHand.MAIN_HAND);
		}
		LivingVillages.debug("Village {} replanted {} at {}", village.getId(), task.order().sapling(), task.spot());
	}

	/** A dirt or grass spot 3–8 blocks outside the house footprint around {@code near}, not in any plot. */
	@Nullable
	private static BlockPos findSpot(ServerLevel level, VillageRecord village, VillageRecord.PendingSapling order, RandomSource random) {
		BoundingBox house = findPlot(village, order.near());
		BoundingBox inner = house.inflatedBy(MIN_DISTANCE);
		BoundingBox outer = house.inflatedBy(MAX_DISTANCE);
		BlockState sapling = BuiltInRegistries.BLOCK.get(order.sapling()).defaultBlockState();
		for (int i = 0; i < SPOT_ATTEMPTS; i++) {
			int x = outer.minX() + random.nextInt(outer.getXSpan());
			int z = outer.minZ() + random.nextInt(outer.getZSpan());
			if (inner.isInside(x, inner.minY(), z) || !level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!insideAnyPlot(level, pos) && isValidSpot(level, pos, sapling)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState sapling) {
		if (!level.isLoaded(pos) || !(sapling.getBlock() instanceof SaplingBlock || sapling.is(BlockTags.SAPLINGS))) {
			return false;
		}
		BlockState ground = level.getBlockState(pos.below());
		return ground.is(BlockTags.DIRT)
				&& SiteFinder.isClearable(level.getBlockState(pos))
				&& level.getBlockState(pos.above()).isAir()
				&& sapling.canSurvive(level, pos);
	}

	/** The plot of the finished house that {@code near} (its footprint centre) belongs to. */
	private static BoundingBox findPlot(VillageRecord village, BlockPos near) {
		for (BoundingBox plot : village.getPlots()) {
			if (plot.isInside(near.getX(), plot.minY(), near.getZ())) {
				return plot;
			}
		}
		return new BoundingBox(near);
	}

	private static boolean insideAnyPlot(ServerLevel level, BlockPos pos) {
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot.inflatedBy(1).isInside(pos.getX(), plot.minY(), pos.getZ())) {
					return true;
				}
			}
			BuildProject project = other.getProject();
			if (project != null && project.getFootprint().inflatedBy(1).isInside(pos.getX(), project.getFootprint().minY(), pos.getZ())) {
				return true;
			}
		}
		return false;
	}

	/** Nearest adult farmer who is not building anything. */
	@Nullable
	private static Villager chooseFarmer(ServerLevel level, VillageRecord village, BlockPos spot) {
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager villager : VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village))) {
			if (villager.getVillagerData().getProfession() != VillagerProfession.FARMER || villager.isTrading() || isBuilder(level, villager)) {
				continue;
			}
			double dist = villager.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			if (dist < bestDist) {
				best = villager;
				bestDist = dist;
			}
		}
		return best;
	}

	private static boolean isBuilder(ServerLevel level, Entity entity) {
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			BuildProject project = other.getProject();
			if (project != null && entity.getUUID().equals(project.getBuilderUuid())) {
				return true;
			}
		}
		return false;
	}
}
