package com.khanhvu.livingvillages.care;

import net.minecraft.nbt.CompoundTag;

/**
 * The target ground height of every column inside a village's frame (spec v4 §8.1), saved with the village. Kept as
 * one byte per column offset from a base height, so even a 160x160 frame costs about 25 KB.
 *
 * <p>It has to be saved rather than worked out again: once part of the ground has been moved, the same computation
 * over the changed world would give a different target, and the village would grade back and forth for ever.
 */
public final class VillageGrading {
	/** A column whose target is further from the base than a byte can hold is left alone. */
	public static final int UNSET = Byte.MIN_VALUE;

	private final int minX;
	private final int minZ;
	private final int width;
	private final int depth;
	private final int baseY;
	private final byte[] offsets;
	/** How many columns of the row-major sweep have been worked. */
	private int cursor;

	public VillageGrading(int minX, int minZ, int width, int depth, int baseY, byte[] offsets, int cursor) {
		this.minX = minX;
		this.minZ = minZ;
		this.width = width;
		this.depth = depth;
		this.baseY = baseY;
		this.offsets = offsets;
		this.cursor = cursor;
	}

	public int columns() {
		return width * depth;
	}

	public int cursor() {
		return cursor;
	}

	public void advance() {
		cursor++;
	}

	public boolean done() {
		return cursor >= columns();
	}

	public int xOf(int index) {
		return minX + index / depth;
	}

	public int zOf(int index) {
		return minZ + index % depth;
	}

	/** Target height of a column, or {@link Integer#MIN_VALUE} when it has none. */
	public int target(int index) {
		return offsets[index] == UNSET ? Integer.MIN_VALUE : baseY + offsets[index];
	}

	/**
	 * Whether this plan still fits the frame. Only that it lies inside it: the inset depends on config, and replanning
	 * after part of the ground has already been moved is exactly what must not happen.
	 */
	public boolean matches(int[] frame) {
		return width > 0 && depth > 0 && minX >= frame[0] && minZ >= frame[1]
				&& minX + width - 1 <= frame[2] && minZ + depth - 1 <= frame[3];
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("MinX", minX);
		tag.putInt("MinZ", minZ);
		tag.putInt("Width", width);
		tag.putInt("Depth", depth);
		tag.putInt("BaseY", baseY);
		tag.putByteArray("Offsets", offsets);
		tag.putInt("Cursor", cursor);
		return tag;
	}

	public static VillageGrading load(CompoundTag tag) {
		int width = tag.getInt("Width");
		int depth = tag.getInt("Depth");
		byte[] offsets = tag.getByteArray("Offsets");
		if (width <= 0 || depth <= 0 || offsets.length != width * depth) {
			return null; // a hand-edited or truncated plan: make a fresh one rather than index out of bounds
		}
		return new VillageGrading(tag.getInt("MinX"), tag.getInt("MinZ"), width, depth, tag.getInt("BaseY"), offsets, tag.getInt("Cursor"));
	}
}
