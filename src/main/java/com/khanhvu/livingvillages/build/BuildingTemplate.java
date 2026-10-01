package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A village building template ready for building: jigsaw blocks already replaced by their final state,
 * structure voids and structure blocks removed. Block positions are template-local (unrotated).
 *
 * @param blocks     blocks of the first palette; air entries are kept only when they must be placed
 * @param contentBox template-local bounds of the non-air blocks. Some packs pad templates with air
 *                   (Better Village templates are 48 tall), so this, not the template size, is the real house size
 * @param floorY     template-local y that sits in the first free block above the ground. Taken from the jigsaw
 *                   that connects the house to the street, exactly like vanilla village generation
 * @param bedCount   number of bed heads, i.e. new beds this building adds
 * @param weight     how many times the template appears in the pool (vanilla weighting)
 * @param kind       what the building is for
 * @param profession the profession whose job site the building holds (workshops and farms), or null
 * @param front      template-local direction the front of the building faces, from the jigsaw vanilla uses to join it
 *                   to a street, or the door when there is none. Null when neither says: any rotation will do
 */
public record BuildingTemplate(
		ResourceLocation id,
		StructureTemplate template,
		List<StructureTemplate.StructureBlockInfo> blocks,
		BoundingBox contentBox,
		int floorY,
		int bedCount,
		int weight,
		BuildingKind kind,
		@Nullable ResourceLocation profession,
		@Nullable Direction front
) {
	BuildingTemplate withWeight(int newWeight) {
		return new BuildingTemplate(id, template, blocks, contentBox, floorY, bedCount, newWeight, kind, profession, front);
	}

	/**
	 * The rotation that turns the building's front towards {@code facing} (spec v4 §6), or null when the template does
	 * not say where its front is, in which case the caller may use any rotation.
	 */
	@Nullable
	public Rotation rotationFacing(Direction facing) {
		if (front == null) {
			return null;
		}
		for (Rotation rotation : Rotation.values()) {
			if (rotation.rotate(front) == facing) {
				return rotation;
			}
		}
		return null; // unreachable for horizontal directions, but a vertical front must not pick a wrong rotation
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
