package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where a house goes. {@code origin} is the world position of template-local (0,0,0) after rotation;
 * {@code footprint} is the world box of the house content (without margin), saved as the village plot.
 */
public record BuildSite(BlockPos origin, Rotation rotation, BoundingBox footprint) {
	public static BuildSite of(HouseTemplate house, BlockPos origin, Rotation rotation) {
		return new BuildSite(origin.immutable(), rotation, house.worldBox(origin, rotation));
	}

	/** World y of the house floor, the first free block above the ground. */
	public int floorY(HouseTemplate house) {
		return origin.getY() + house.floorY();
	}
}
