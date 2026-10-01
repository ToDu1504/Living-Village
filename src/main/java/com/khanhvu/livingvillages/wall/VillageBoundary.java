package com.khanhvu.livingvillages.wall;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The outline of a village (spec v4 §4): the fixed, axis-aligned rectangle the village grows into, taken once from
 * the extent of the vanilla village. Also the geometry the walls need: point-in-polygon tests and the 4-connected
 * column loop along the outline. Because the outline is a rectangle, {@link #containsBox} is exact.
 */
public final class VillageBoundary {
	private VillageBoundary() {
	}

	/**
	 * The rectangle a village grows into, as {minX, minZ, maxX, maxZ}: the extent of its bell, beds and job sites
	 * widened by {@code wallMargin} and clamped to {@code [frameMinSize, frameMaxSize]} about its centre. Taken once
	 * and then kept for the life of the village, so the walls never move.
	 */
	public static int[] frame(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		BlockPos bell = village.getBellPos();
		int minX = bell.getX();
		int maxX = bell.getX();
		int minZ = bell.getZ();
		int maxZ = bell.getZ();
		// Only the vanilla village counts: the frame is taken before the mod has built anything.
		for (PoiRecord poi : level.getPoiManager()
				.getInRange(type -> type.is(PoiTypes.HOME) || type.is(PoiTypes.MEETING) || type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
						bell, VillageAnalyzer.areaRadius(village), PoiManager.Occupancy.ANY)
				.toList()) {
			BlockPos pos = poi.getPos();
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		minX -= config.wallMargin;
		maxX += config.wallMargin;
		minZ -= config.wallMargin;
		maxZ += config.wallMargin;
		int[] x = clampSide(minX, maxX, config.frameMinSize, config.frameMaxSize);
		int[] z = clampSide(minZ, maxZ, config.frameMinSize, config.frameMaxSize);
		return new int[] {x[0], z[0], x[1], z[1]};
	}

	/** One side of the frame grown or shrunk about its centre to an even length within [min, max]. */
	private static int[] clampSide(int low, int high, int min, int max) {
		int centreTwice = low + high;
		int size = Math.clamp(high - low, min, max);
		if (size % 2 != 0) {
			size++; // even, so the centre lands on a block boundary and both halves match
		}
		int half = size / 2;
		// centreTwice may be odd; the floor division keeps the frame within one block of the true centre.
		int centre = Math.floorDiv(centreTwice, 2);
		return new int[] {centre - half, centre + half};
	}

	/** The frame as a polygon {x0, z0, x1, z1, ...} for {@link WallRing}. Winding does not matter to any user of it. */
	public static int[] polygonOf(int[] frame) {
		return new int[] {frame[0], frame[1], frame[2], frame[1], frame[2], frame[3], frame[0], frame[3]};
	}

	/** Whether every chunk within {@code radius} of the bell is loaded, so a POI scan sees the whole village. */
	public static boolean areaLoaded(ServerLevel level, BlockPos bell, int radius) {
		for (int cx = (bell.getX() - radius) >> 4; cx <= (bell.getX() + radius) >> 4; cx++) {
			for (int cz = (bell.getZ() - radius) >> 4; cz <= (bell.getZ() + radius) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return false;
				}
			}
		}
		return true;
	}

	/** Whether the column (x, z) is inside the polygon (even-odd rule, block centres). */
	public static boolean contains(int[] polygon, double x, double z) {
		boolean inside = false;
		int n = polygon.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = polygon[i * 2];
			double zi = polygon[i * 2 + 1];
			double xj = polygon[j * 2];
			double zj = polygon[j * 2 + 1];
			if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
				inside = !inside;
			}
		}
		return inside;
	}

	/** Whether the whole box (its four corners) lies inside the polygon. */
	public static boolean containsBox(int[] polygon, BoundingBox box) {
		return contains(polygon, box.minX() + 0.5, box.minZ() + 0.5) && contains(polygon, box.minX() + 0.5, box.maxZ() + 0.5)
				&& contains(polygon, box.maxX() + 0.5, box.minZ() + 0.5) && contains(polygon, box.maxX() + 0.5, box.maxZ() + 0.5);
	}

	/**
	 * The columns along the outline, in order, as {x0, z0, x1, z1, ...}: a closed loop in which each column touches
	 * the next on a side (never only on a corner), so a fence along it has no diagonal gap. Deterministic.
	 */
	public static int[] columns(int[] polygon) {
		List<int[]> cells = new ArrayList<>();
		int n = polygon.length / 2;
		for (int i = 0; i < n; i++) {
			int x0 = polygon[i * 2];
			int z0 = polygon[i * 2 + 1];
			int x1 = polygon[((i + 1) % n) * 2];
			int z1 = polygon[((i + 1) % n) * 2 + 1];
			line(x0, z0, x1, z1, cells);
		}
		// Each edge starts where the previous one ended: drop repeated cells, including the loop closing on itself.
		List<int[]> loop = new ArrayList<>();
		for (int[] cell : cells) {
			if (loop.isEmpty() || !same(loop.get(loop.size() - 1), cell)) {
				loop.add(cell);
			}
		}
		while (loop.size() > 1 && same(loop.get(0), loop.get(loop.size() - 1))) {
			loop.remove(loop.size() - 1);
		}
		int[] result = new int[loop.size() * 2];
		for (int i = 0; i < loop.size(); i++) {
			result[i * 2] = loop.get(i)[0];
			result[i * 2 + 1] = loop.get(i)[1];
		}
		return result;
	}

	private static boolean same(int[] a, int[] b) {
		return a[0] == b[0] && a[1] == b[1];
	}

	/** 4-connected line from (x0, z0) to (x1, z1), both ends included: a diagonal step becomes two side steps. */
	private static void line(int x0, int z0, int x1, int z1, List<int[]> out) {
		int dx = Math.abs(x1 - x0);
		int dz = Math.abs(z1 - z0);
		int sx = Integer.signum(x1 - x0);
		int sz = Integer.signum(z1 - z0);
		int x = x0;
		int z = z0;
		out.add(new int[] {x, z});
		// Walk the grid, choosing the axis whose next boundary the ideal line crosses first.
		for (int ix = 0, iz = 0; ix < dx || iz < dz; ) {
			if ((0.5 + ix) * dz < (0.5 + iz) * dx) {
				x += sx;
				ix++;
			} else {
				z += sz;
				iz++;
			}
			out.add(new int[] {x, z});
		}
	}
}
