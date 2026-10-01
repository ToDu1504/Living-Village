package com.khanhvu.livingvillages.road;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.PlotBuild;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;

/**
 * Takes a building of the mod's own back down so a new branch street can get through (spec v4 §8.4): the road wins
 * over the house, and the village then wants a new one, which stage 5's placer puts on a street.
 *
 * <p>Only blocks that still stand exactly as the mod placed them are removed, so anything a player has changed inside
 * the house stays. Nothing drops, as when a tree is felled for a building site. A building the mod has no record of
 * (one from a world before v4, a vanilla house, anything a player made) is never touched.
 */
public final class Demolisher {
	private Demolisher() {
	}

	/** Whether this plot may be taken down for a road: only one the mod built and still has the recipe for. */
	public static boolean canDemolish(ServerLevel level, VillageRecord village, BoundingBox plot) {
		if (!LVConfig.get().roadsMayDemolish) {
			return false;
		}
		PlotBuild build = village.buildOf(plot);
		return build != null && template(level, village, build) != null;
	}

	/**
	 * Removes the mod's own blocks of the plot and forgets it. The step list is not needed: the template's blocks,
	 * turned the way they were placed, say exactly what the mod put where.
	 */
	public static boolean demolish(ServerLevel level, VillageRegistry registry, VillageRecord village, BoundingBox plot) {
		PlotBuild build = village.buildOf(plot);
		BuildingTemplate template = build == null ? null : template(level, village, build);
		if (template == null) {
			return false;
		}
		int removed = 0;
		int kept = 0;
		for (StructureTemplate.StructureBlockInfo info : template.blocks()) {
			if (info.state().isAir()) {
				continue;
			}
			BlockPos pos = BuildingTemplate.toWorld(info.pos(), build.origin(), build.rotation());
			if (!level.isLoaded(pos)) {
				return false; // come back when the whole building is loaded, so none of it is left half standing
			}
			BlockState placed = info.state().rotate(build.rotation());
			BlockState present = level.getBlockState(pos);
			if (present.getBlock() != placed.getBlock()) {
				kept++; // a player changed this block, or the ground took it: leave it alone
				continue;
			}
			level.destroyBlock(pos, false);
			removed++;
		}
		village.removePlot(plot);
		registry.setDirty();
		Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.demolished");
		LivingVillages.debug("Village {}: took down the building at {} for a road, {} blocks removed, {} left as found",
				village.getId(), plot, removed, kept);
		return true;
	}

	private static BuildingTemplate template(ServerLevel level, VillageRecord village, PlotBuild build) {
		List<BuildingTemplate> all = BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village));
		for (BuildingTemplate candidate : all) {
			if (candidate.id().equals(build.templateId())) {
				return candidate;
			}
		}
		return null;
	}
}
