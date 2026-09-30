package com.khanhvu.livingvillages.care;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.build.TreeFeller;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.wall.VillageWalls;
import com.khanhvu.livingvillages.wall.WallBuilder;
import com.khanhvu.livingvillages.wall.WallShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Garden fences around the houses the mod built (spec v3-GĐ 6, off by default with {@code homeFences}): a fence
 * homeFenceGap blocks out from the footprint, open in front of the door (villagers cannot open fence gates, so the
 * way in is a gap). Never across roads, water, other plots or man-made ground; never around vanilla or players'
 * houses, which have no plot. Fence blocks are remembered with the wall blocks: one a player breaks stays open.
 */
public final class HomeFences {
	/** Wall-block entries with this ring id are garden fences. */
	public static final int FENCE_RING = VillageWalls.MAX_RING_ID;

	private HomeFences() {
	}

	public static boolean enabled() {
		return LVConfig.get().homeFences && !WallBuilder.deferred();
	}

	/** Called every tick for villages near a player: a few fence blocks for the first house without its fence. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		if (!enabled() || Math.floorMod(level.getGameTime() + village.getId().hashCode() / 3, config.roadCareIntervalTicks) != 0) {
			return;
		}
		VillageWalls walls = village.getWalls();
		for (BoundingBox plot : village.getPlots()) {
			if (village.getFieldPlots().contains(plot) || plot.equals(village.getGraveyard()) || walls.getFencedPlots().contains(plot)) {
				continue;
			}
			if (fence(level, village, plot, config.careBlocksPerRun)) {
				walls.getFencedPlots().add(plot);
				LivingVillages.debug("Village {}: garden fence around {} done", village.getId(), plot);
			}
			VillageRegistry.get(level).setDirty();
			return;
		}
	}

	/** A fence block that went missing (not by a player's hand): the house's fence is looked at again. */
	public static void onLost(VillageWalls walls, BlockPos pos) {
		int reach = LVConfig.get().homeFenceGap + 1;
		walls.getFencedPlots().removeIf(plot -> plot.inflatedBy(reach).isInside(pos.getX(), plot.minY(), pos.getZ()));
	}

	/** Places up to {@code budget} fence blocks around the plot; true when the whole line is settled. */
	private static boolean fence(ServerLevel level, VillageRecord village, BoundingBox plot, int budget) {
		BoundingBox line = plot.inflatedBy(LVConfig.get().homeFenceGap + 1);
		BlockPos opening = opening(level, plot, line);
		Block fence = WallShapes.blocks(village).palisade();
		VillageWalls walls = village.getWalls();
		for (int[] cell : perimeter(line)) {
			int x = cell[0];
			int z = cell[1];
			if (opening != null && x == opening.getX() && z == opening.getZ()) {
				continue;
			}
			if (!level.hasChunk(x >> 4, z >> 4)) {
				return false;
			}
			BlockPos ground = new BlockPos(x, SiteFinder.groundTop(level, x, z), z);
			BlockPos pos = ground.above();
			long key = pos.asLong();
			BlockState current = level.getBlockState(pos);
			if (current.getBlock() == fence || walls.getAbandoned().contains(key)) {
				continue;
			}
			BlockState groundState = level.getBlockState(ground);
			boolean fits = SiteFinder.isNaturalGround(groundState) && groundState.getFluidState().isEmpty()
					&& !RoadBuilder.roadBlocks().contains(groundState.getBlock()) && !insideOtherPlot(level, plot, x, z)
					&& (SiteFinder.isClearable(current) || TreeFeller.isNaturalLeaves(current));
			if (!fits) {
				continue;
			}
			if (budget-- <= 0) {
				return false;
			}
			level.setBlock(pos, Block.updateFromNeighbourShapes(fence.defaultBlockState(), level, pos), Block.UPDATE_ALL);
			walls.addBlock(key, FENCE_RING, 0);
		}
		return true;
	}

	/** Cells along the edge of the box, around it. */
	private static List<int[]> perimeter(BoundingBox box) {
		List<int[]> cells = new ArrayList<>();
		for (int x = box.minX(); x <= box.maxX(); x++) {
			cells.add(new int[] {x, box.minZ()});
			cells.add(new int[] {x, box.maxZ()});
		}
		for (int z = box.minZ() + 1; z < box.maxZ(); z++) {
			cells.add(new int[] {box.minX(), z});
			cells.add(new int[] {box.maxX(), z});
		}
		return cells;
	}

	/** The fence cell in front of the house door (the way in stays open), or null when the house has no door. */
	@Nullable
	private static BlockPos opening(ServerLevel level, BoundingBox plot, BoundingBox line) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = plot.minX(); x <= plot.maxX(); x++) {
			for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
				for (int y = plot.minY(); y <= plot.maxY(); y++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
						continue;
					}
					Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
					for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
						BlockPos outside = pos.relative(side);
						if (!plot.isInside(outside)) {
							// Straight out from the door to the fence line.
							int fx = side.getStepX() > 0 ? line.maxX() : side.getStepX() < 0 ? line.minX() : outside.getX();
							int fz = side.getStepZ() > 0 ? line.maxZ() : side.getStepZ() < 0 ? line.minZ() : outside.getZ();
							return new BlockPos(fx, 0, fz);
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean insideOtherPlot(ServerLevel level, BoundingBox own, int x, int z) {
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot != own && plot.isInside(x, plot.minY(), z)) {
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
}
