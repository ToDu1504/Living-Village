package com.khanhvu.livingvillages.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * What a finished building was built from (spec v4 §8.4), so the mod can take exactly its own blocks back when a road
 * has to pass through it. Recording the template, origin and rotation rather than every block keeps this to a few
 * numbers per building: the step list is deterministic, so it can always be worked out again.
 *
 * <p>A building from a world before v4 has no record, and so can never be demolished. That is deliberate: it also
 * protects a village the player already knows.
 */
public record PlotBuild(BoundingBox box, ResourceLocation templateId, BlockPos origin, Rotation rotation) {

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("Box", new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
		tag.putString("Template", templateId.toString());
		tag.put("Origin", NbtUtils.writeBlockPos(origin));
		tag.putString("Rotation", rotation.getSerializedName());
		return tag;
	}

	@Nullable
	public static PlotBuild load(CompoundTag tag) {
		int[] a = tag.getIntArray("Box");
		ResourceLocation id = ResourceLocation.tryParse(tag.getString("Template"));
		if (a.length != 6 || id == null || !tag.contains("Origin", Tag.TAG_INT_ARRAY)) {
			return null;
		}
		BlockPos origin = NbtUtils.readBlockPos(tag, "Origin").orElse(null);
		if (origin == null) {
			return null;
		}
		Rotation rotation = Rotation.NONE;
		for (Rotation r : Rotation.values()) {
			if (r.getSerializedName().equals(tag.getString("Rotation"))) {
				rotation = r;
			}
		}
		return new PlotBuild(new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]), id, origin, rotation);
	}
}
