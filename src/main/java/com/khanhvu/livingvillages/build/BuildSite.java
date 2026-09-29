package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Comparator;
import java.util.List;

/**
 * Where a house goes. {@code origin} is the world position of template-local (0,0,0) after rotation;
 * {@code footprint} is the world box of the house content (without margin), saved as the village plot.
 * {@code treeRoots} are the natural trees inside the footprint that must be felled first, sorted so the build
 * order stays deterministic.
 */
public record BuildSite(BlockPos origin, Rotation rotation, BoundingBox footprint, List<BlockPos> treeRoots) {
	public static BuildSite of(HouseTemplate house, BlockPos origin, Rotation rotation) {
		return new BuildSite(origin.immutable(), rotation, house.worldBox(origin, rotation), List.of());
	}

	public BuildSite withTrees(List<BlockPos> roots) {
		List<BlockPos> sorted = roots.stream()
				.map(BlockPos::immutable)
				.sorted(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()))
				.toList();
		return new BuildSite(origin, rotation, footprint, sorted);
	}

	/** World y of the house floor, the first free block above the ground. */
	public int floorY(HouseTemplate house) {
		return origin.getY() + house.floorY();
	}
}
