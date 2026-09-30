package com.khanhvu.livingvillages.village;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers villages by their bell (POI "meeting") around players, and tracks bells being removed or replaced.
 * Never searches outside scanRadius of a player.
 */
public final class VillageScanner {
	private VillageScanner() {
	}

	public static void scan(ServerLevel level) {
		LVConfig config = LVConfig.get();
		VillageRegistry registry = VillageRegistry.get(level);
		PoiManager poi = level.getPoiManager();
		Set<BlockPos> seenBells = new HashSet<>();

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			BlockPos playerPos = player.blockPosition();

			checkKnownBells(level, registry, poi, playerPos, config.scanRadius);

			List<BlockPos> bells = poi.findAll(h -> h.is(PoiTypes.MEETING), pos -> true, playerPos, config.scanRadius, PoiManager.Occupancy.ANY)
					.map(BlockPos::immutable)
					.toList();
			for (BlockPos bell : bells) {
				if (seenBells.add(bell)) {
					handleBell(level, registry, bell, config);
				}
			}
		}
	}

	/** Marks villages inactive when their bell is gone. Only checks bells in loaded chunks near the player. */
	private static void checkKnownBells(ServerLevel level, VillageRegistry registry, PoiManager poi, BlockPos playerPos, int radius) {
		for (VillageRecord record : registry.getVillages()) {
			BlockPos bell = record.getBellPos();
			if (!record.isActive() || record.horizontalDistSqr(playerPos) > (double) radius * radius || !level.isLoaded(bell)) {
				continue;
			}
			if (!poi.existsAtPosition(PoiTypes.MEETING, bell)) {
				record.setActive(false);
				// A running project is kept and resumes if a bell comes back; the builder is let go meanwhile.
				if (record.getProject() != null) {
					BuilderAssignment.release(level, record.getProject());
				}
				registry.setDirty();
				LivingVillages.debug("Village {} lost its bell at {}, now inactive", record.getId(), bell);
			}
		}
	}

	private static void handleBell(ServerLevel level, VillageRegistry registry, BlockPos bell, LVConfig config) {
		if (!level.isLoaded(bell)) {
			return;
		}
		VillageRecord existing = registry.findNearest(bell, config.villageMergeRadius);
		if (existing != null) {
			// A bell placed back near an inactive village revives it (keeping houses, plots and style).
			if (!existing.isActive()) {
				existing.setBellPos(bell);
				existing.setActive(true);
				registry.setDirty();
				LivingVillages.debug("Village {} reactivated with bell at {}", existing.getId(), bell);
			}
			return;
		}

		if (VillageAnalyzer.getAdultVillagers(level, bell, config.villageRadius).isEmpty()) {
			return;
		}
		VillageRecord record = registry.register(bell);
		VillageAnalyzer.Stats stats = VillageAnalyzer.analyze(level, record);
		VillageAnalyzer.ensureVillageType(record, stats, registry);
		VillageLevel.update(level, record, stats); // first computation: sets the starting level silently
		LivingVillages.debug("Registered village {} at {} ({}), {} adults, {} beds",
				record.getId(), bell, record.getVillageType(), stats.adultVillagers(), stats.totalBeds());
	}
}
