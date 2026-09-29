package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;

/**
 * A house template ready for building: jigsaw blocks already replaced by their final state,
 * structure voids and structure blocks removed. Block positions are template-local (unrotated).
 *
 * @param blocks     blocks of the first palette; air entries are kept only when they must be placed
 * @param contentBox template-local bounds of the non-air blocks. Some packs pad templates with air
 *                   (Better Village templates are 48 tall), so this, not the template size, is the real house size
 * @param floorY     template-local y that sits in the first free block above the ground. Taken from the jigsaw
 *                   that connects the house to the street, exactly like vanilla village generation
 * @param bedCount   number of bed heads, i.e. new beds this house adds
 * @param weight     how many times the template appears in the pool (vanilla weighting)
 */
public record HouseTemplate(
		ResourceLocation id,
		StructureTemplate template,
		List<StructureTemplate.StructureBlockInfo> blocks,
		BoundingBox contentBox,
		int floorY,
		int bedCount,
		int weight
) {
	HouseTemplate withWeight(int newWeight) {
		return new HouseTemplate(id, template, blocks, contentBox, floorY, bedCount, newWeight);
	}

	/** World position of a template-local position, rotating around the template origin like jigsaw placement. */
	public static BlockPos toWorld(BlockPos local, BlockPos origin, Rotation rotation) {
		return StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
	}

	/** World bounds of the house content when placed at {@code origin} with {@code rotation}. */
	public BoundingBox worldBox(BlockPos origin, Rotation rotation) {
		BlockPos a = toWorld(new BlockPos(contentBox.minX(), contentBox.minY(), contentBox.minZ()), origin, rotation);
		BlockPos b = toWorld(new BlockPos(contentBox.maxX(), contentBox.maxY(), contentBox.maxZ()), origin, rotation);
		return BoundingBox.fromCorners(a, b);
	}
}
