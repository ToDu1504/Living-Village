package com.khanhvu.livingvillages.road;

import net.minecraft.core.BlockPos;

/**
 * One planned village road (spec v4 §5.2), always axis-aligned: either {@code x1 == x2} or {@code z1 == z2}. An axis
 * is one of the four main roads from the square to a gate; the rest are branches opened when the frontage along the
 * roads runs out.
 */
public record RoadSegment(int x1, int z1, int x2, int z2, boolean axis) {
	public RoadSegment {
		if (x1 != x2 && z1 != z2) {
			throw new IllegalArgumentException("road segment must be axis-aligned: " + x1 + "," + z1 + " to " + x2 + "," + z2);
		}
	}

	/** Ordered from {@code (x1, z1)} towards {@code (x2, z2)}, so a road is paved outwards from the square. */
	public static RoadSegment of(int x1, int z1, int x2, int z2, boolean axis) {
		return new RoadSegment(x1, z1, x2, z2, axis);
	}

	public boolean horizontal() {
		return z1 == z2;
	}

	/** Number of columns on the road, both ends included. */
	public int length() {
		return (horizontal() ? Math.abs(x2 - x1) : Math.abs(z2 - z1)) + 1;
	}

	/** The column {@code i} steps from the near end; y is left at 0, the caller finds the ground. */
	public BlockPos cell(int i) {
		int step = horizontal() ? Integer.signum(x2 - x1) : Integer.signum(z2 - z1);
		return horizontal() ? new BlockPos(x1 + step * i, 0, z1) : new BlockPos(x1, 0, z1 + step * i);
	}

	/** Whether the column lies on this road. */
	public boolean covers(int x, int z) {
		return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
	}

	public int[] toIntArray() {
		return new int[] {x1, z1, x2, z2, axis ? 1 : 0};
	}

	public static RoadSegment fromIntArray(int[] a) {
		return new RoadSegment(a[0], a[1], a[2], a[3], a[4] != 0);
	}
}
