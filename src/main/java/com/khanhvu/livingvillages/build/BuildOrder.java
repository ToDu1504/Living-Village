package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a house + site into an ordered list of steps: foundation, clearing, structural blocks (by rising y),
 * then blocks that need support. Fully deterministic (no randomness, no world reads), because only the step
 * index is saved and the list is rebuilt after a reload.
 */
public final class BuildOrder {
	/** A foundation column never goes deeper than this below the floor. */
	public static final int MAX_FOUNDATION_DEPTH = 8;

	/** Rising y, then x, then z. */
	private static final Comparator<BuildStep.Place> BY_LAYER = Comparator
			.comparingInt((BuildStep.Place p) -> p.anchor().getY())
			.thenComparingInt(p -> p.anchor().getX())
			.thenComparingInt(p -> p.anchor().getZ());

	private BuildOrder() {
	}

	public static List<BuildStep> create(HouseTemplate house, BuildSite site, BlockState foundation) {
		int floorY = site.floorY(house);
		BoundingBox footprint = site.footprint();

		Map<BlockPos, BuildStep.Placement> placements = new HashMap<>();
		// Per column: the lowest template block under the floor, or MAX_VALUE if the column only has blocks above it.
		Map<Long, Integer> columns = new HashMap<>();
		for (StructureTemplate.StructureBlockInfo info : house.blocks()) {
			if (info.state().isAir()) {
				continue; // clearing already empties the house volume
			}
			BlockPos pos = HouseTemplate.toWorld(info.pos(), site.origin(), site.rotation());
			placements.put(pos, new BuildStep.Placement(pos, info.state().rotate(site.rotation()), info.nbt()));
			long column = BlockPos.asLong(pos.getX(), 0, pos.getZ());
			int lowest = pos.getY() < floorY ? pos.getY() : Integer.MAX_VALUE;
			columns.merge(column, lowest, Math::min);
		}

		List<BuildStep> steps = new ArrayList<>();

		// 1. Foundation, only under columns that hold part of the house.
		List<Long> columnKeys = new ArrayList<>(columns.keySet());
		columnKeys.sort(Comparator.comparingInt((Long c) -> BlockPos.getX(c)).thenComparingInt(c -> BlockPos.getZ(c)));
		for (long column : columnKeys) {
			int lowest = columns.get(column);
			int top = (lowest == Integer.MAX_VALUE ? floorY : lowest) - 1;
			steps.add(new BuildStep.Foundation(BlockPos.getX(column), BlockPos.getZ(column), top, foundation));
		}

		// 2. Clearing the whole house volume above the floor.
		for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
				steps.add(new BuildStep.Clear(x, z, floorY, footprint.maxY()));
			}
		}

		// 3 + 4. Template blocks, two-part blocks grouped into one step.
		List<BuildStep.Place> structural = new ArrayList<>();
		List<BuildStep.Place> dependent = new ArrayList<>();
		Set<BlockPos> used = new HashSet<>();
		List<BlockPos> positions = new ArrayList<>(placements.keySet());
		positions.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
		for (BlockPos pos : positions) {
			if (used.contains(pos)) {
				continue;
			}
			BuildStep.Placement first = placements.get(pos);
			List<BuildStep.Placement> group = new ArrayList<>(2);
			group.add(first);
			used.add(pos);
			BuildStep.Placement other = otherHalf(first, placements);
			if (other != null && !used.contains(other.pos())) {
				used.add(other.pos());
				// Foot before head, lower before upper.
				if (isSecondHalf(first.state())) {
					group.add(0, other);
				} else {
					group.add(other);
				}
			}
			boolean belowFloor = group.stream().allMatch(p -> p.pos().getY() < floorY);
			BuildStep.Place step = new BuildStep.Place(List.copyOf(group), belowFloor);
			if (group.stream().anyMatch(p -> needsSupport(p.state()))) {
				dependent.add(step);
			} else {
				structural.add(step);
			}
		}
		structural.sort(BY_LAYER);
		dependent.sort(BY_LAYER);
		steps.addAll(structural);
		steps.addAll(dependent);
		return List.copyOf(steps);
	}

	@Nullable
	private static BuildStep.Placement otherHalf(BuildStep.Placement placement, Map<BlockPos, BuildStep.Placement> placements) {
		BlockState state = placement.state();
		BlockPos otherPos;
		if (state.getBlock() instanceof BedBlock) {
			otherPos = placement.pos().relative(BedBlock.getConnectedDirection(state));
		} else if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			otherPos = placement.pos().relative(state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
					? Direction.UP : Direction.DOWN);
		} else {
			return null;
		}
		BuildStep.Placement other = placements.get(otherPos);
		return other != null && other.state().is(state.getBlock()) ? other : null;
	}

	private static boolean isSecondHalf(BlockState state) {
		if (state.getBlock() instanceof BedBlock) {
			return state.getValue(BedBlock.PART) == BedPart.HEAD;
		}
		return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
	}

	/**
	 * Blocks that pop off without a supporting block, placed after all structural blocks.
	 * Chosen by block class rather than collision shape, so stairs, slabs, fences and panes stay with the
	 * structure (a roof is not built last) while torches, doors, beds, carpets, plants... wait for it.
	 */
	private static boolean needsSupport(BlockState state) {
		Block block = state.getBlock();
		return block instanceof BedBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
				|| block instanceof BaseTorchBlock || block instanceof LanternBlock || block instanceof CarpetBlock
				|| block instanceof FlowerPotBlock || block instanceof SignBlock || block instanceof LadderBlock
				|| block instanceof FaceAttachedHorizontalDirectionalBlock || block instanceof BushBlock
				|| block instanceof BasePressurePlateBlock || block instanceof BaseRailBlock || block instanceof BellBlock
				|| block instanceof AbstractCandleBlock || block instanceof SnowLayerBlock || block instanceof AbstractBannerBlock
				|| block instanceof VineBlock || block instanceof RedStoneWireBlock || block instanceof TripWireHookBlock
				|| block instanceof DiodeBlock || block instanceof CakeBlock
				|| state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
	}
}
