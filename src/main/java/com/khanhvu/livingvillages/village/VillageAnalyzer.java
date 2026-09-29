package com.khanhvu.livingvillages.village;

import com.khanhvu.livingvillages.config.LVConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Counts villagers and beds around a village bell. All queries are bounded by the village area radius ({@link #areaRadius}).
 */
public final class VillageAnalyzer {
	private static final int VERTICAL_RANGE = 24;
	/** Slack so beds near a plot corner are not missed because of height differences (POI range is spherical). */
	private static final int PLOT_RADIUS_MARGIN = 4;

	private VillageAnalyzer() {
	}

	/**
	 * @param dominantType majority style of the adult villagers present, or null if there are none
	 * @param adults       the adult villagers counted (loaded entities)
	 */
	public record Stats(int adultVillagers, int totalBeds, int freeBeds, @Nullable VillageType dominantType, List<Villager> adults) {
	}

	public static Stats analyze(ServerLevel level, VillageRecord record) {
		int radius = areaRadius(record);
		BlockPos bell = record.getBellPos();

		List<Villager> adults = getAdultVillagers(level, bell, radius);
		PoiManager poi = level.getPoiManager();
		int totalBeds = (int) poi.getCountInRange(h -> h.is(PoiTypes.HOME), bell, radius, PoiManager.Occupancy.ANY);
		int freeBeds = (int) poi.getCountInRange(h -> h.is(PoiTypes.HOME), bell, radius, PoiManager.Occupancy.HAS_SPACE);

		return new Stats(adults.size(), totalBeds, freeBeds, dominantType(adults), adults);
	}

	/**
	 * villageRadius, widened to cover every house this mod built for the village: maxBuildDistance may exceed
	 * villageRadius, and the beds of a new house must count or the village would keep building.
	 */
	public static int areaRadius(VillageRecord record) {
		double maxDistSqr = 0;
		for (BoundingBox plot : record.getPlots()) {
			for (int x : new int[] {plot.minX(), plot.maxX()}) {
				for (int z : new int[] {plot.minZ(), plot.maxZ()}) {
					maxDistSqr = Math.max(maxDistSqr, record.horizontalDistSqr(new BlockPos(x, 0, z)));
				}
			}
		}
		return Math.max(LVConfig.get().villageRadius, Mth.ceil(Math.sqrt(maxDistSqr)) + PLOT_RADIUS_MARGIN);
	}

	/** Locks in the village style the first time villagers are seen; later visitors cannot change it. */
	public static void ensureVillageType(VillageRecord record, Stats stats, VillageRegistry registry) {
		if (record.getVillageType() == null && stats.dominantType() != null) {
			record.setVillageType(stats.dominantType());
			registry.setDirty();
		}
	}

	public static List<Villager> getAdultVillagers(ServerLevel level, BlockPos center, int radius) {
		AABB box = new AABB(center).inflate(radius, VERTICAL_RANGE, radius);
		return level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby());
	}

	@Nullable
	private static VillageType dominantType(List<Villager> villagers) {
		Map<VillageType, Integer> counts = new EnumMap<>(VillageType.class);
		for (Villager villager : villagers) {
			counts.merge(VillageType.fromVillagerType(villager.getVillagerData().getType()), 1, Integer::sum);
		}
		VillageType best = null;
		int bestCount = 0;
		// EnumMap iterates in declaration order, so ties resolve deterministically.
		for (Map.Entry<VillageType, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > bestCount) {
				best = entry.getKey();
				bestCount = entry.getValue();
			}
		}
		return best;
	}
}
