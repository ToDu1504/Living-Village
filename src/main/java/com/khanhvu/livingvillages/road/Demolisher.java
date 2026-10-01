package com.khanhvu.livingvillages.road;

import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVLog;
import com.khanhvu.livingvillages.village.PlotBuild;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

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
		int[] counts = removeTemplateBlocks(level, template, build.origin(), build.rotation());
		if (counts == null) {
			return false; // come back when the whole building is loaded, so none of it is left half standing
		}
		village.removePlot(plot);
		registry.setDirty();
		Chronicle.add(level, village, Chronicle.Notice.SMALL, "livingvillages.chronicle.demolished");
		LVLog.milestone(LVLog.Category.ROAD, village, "took down the building at {} for a road, {} blocks removed, {} left as found",
				plot, counts[0], counts[1]);
		return true;
	}

	/**
	 * Removes every block of {@code template} placed at {@code origin}/{@code rotation} that still matches it
	 * (by block type), nothing dropped. Shared by {@link #demolish} (the mod's own plots) and
	 * {@code VillageRelocator} (a world-gen house, spec v4 mini-update M5), which has no {@link PlotBuild} to
	 * look up since the mod never built it. Null if any of the building is in an unloaded chunk; otherwise
	 * {blocks removed, blocks left because the world no longer matched there}.
	 */
	public static int[] removeTemplateBlocks(ServerLevel level, BuildingTemplate template, BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {
		int removed = 0;
		int kept = 0;
		for (StructureTemplate.StructureBlockInfo info : template.blocks()) {
			if (info.state().isAir()) {
				continue;
			}
			BlockPos pos = BuildingTemplate.toWorld(info.pos(), origin, rotation);
			if (!level.isLoaded(pos)) {
				return null;
			}
			BlockState placed = info.state().rotate(rotation);
			BlockState present = level.getBlockState(pos);
			if (present.getBlock() != placed.getBlock()) {
				kept++; // a player changed this block, or the ground took it: leave it alone
				continue;
			}
			level.destroyBlock(pos, false);
			removed++;
		}
		return new int[] {removed, kept};
	}

	private static BuildingTemplate template(ServerLevel level, VillageRecord village, PlotBuild build) {
		return templateById(level, village, build.templateId());
	}

	/** The building template with this id in the village's pool, or null if none (e.g. a datapack change). */
	@Nullable
	public static BuildingTemplate templateById(ServerLevel level, VillageRecord village, net.minecraft.resources.ResourceLocation id) {
		for (BuildingTemplate candidate : BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village))) {
			if (candidate.id().equals(id)) {
				return candidate;
			}
		}
		return null;
	}
}
