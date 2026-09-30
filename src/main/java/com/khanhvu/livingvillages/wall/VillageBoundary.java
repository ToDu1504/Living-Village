package com.khanhvu.livingvillages.wall;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The outline of a village (spec v3 §4): the convex hull of its buildings, beds, job sites and bell on the XZ
 * plane, widened by {@code wallMargin} and simplified to at most {@code wallMaxVertices} corners. Also the geometry
 * the walls need: point-in-polygon tests and the 4-connected column loop along the outline.
 */
public final class VillageBoundary {
	/** Corners of the octagon put around every point to widen the hull by the margin. */
	private static final int WIDEN_CORNERS = 8;
	/** Fewer points than this: a circle around the bell instead of a hull. */
	private static final int MIN_POINTS = 3;
	private static final int CIRCLE_EXTRA_RADIUS = 12;
	private static final int CIRCLE_CORNERS = 16;

	private VillageBoundary() {
	}

	/**
	 * Polygon around the village, counter-clockwise, as {x0, z0, x1, z1, ...}. Only the plots {@code plotFilter} accepts
	 * count (a palisade encloses every plot; city walls will leave farms and pens outside).
	 */
	public static int[] compute(ServerLevel level, VillageRecord village, Predicate<BoundingBox> plotFilter) {
		LVConfig config = LVConfig.get();
		List<long[]> points = new ArrayList<>();
		BlockPos bell = village.getBellPos();
		points.add(new long[] {bell.getX(), bell.getZ()});
		for (int i = 0; i < village.getPlots().size(); i++) {
			BoundingBox plot = village.getPlots().get(i);
			if (!plotFilter.test(plot)) {
				continue;
			}
			points.add(new long[] {plot.minX(), plot.minZ()});
			points.add(new long[] {plot.minX(), plot.maxZ()});
			points.add(new long[] {plot.maxX(), plot.minZ()});
			points.add(new long[] {plot.maxX(), plot.maxZ()});
		}
		int radius = VillageAnalyzer.areaRadius(village);
		level.getPoiManager().getInRange(type -> type.is(PoiTypes.HOME) || type.is(PoiTypes.MEETING) || type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
						bell, radius, PoiManager.Occupancy.ANY)
				.forEach(poi -> points.add(new long[] {poi.getPos().getX(), poi.getPos().getZ()}));

		int margin = config.wallMargin;
		if (points.size() < MIN_POINTS) {
			return circle(bell.getX(), bell.getZ(), margin + CIRCLE_EXTRA_RADIUS);
		}
		return widenedHull(points, margin, config.wallMaxVertices);
	}

	/** The polygon widened by {@code by} blocks all around: the outline of an outer ring (spec v3-GĐ 4). */
	public static int[] expand(int[] polygon, int by) {
		List<long[]> points = new ArrayList<>();
		for (int i = 0; i < polygon.length; i += 2) {
			points.add(new long[] {polygon[i], polygon[i + 1]});
		}
		return widenedHull(points, by, LVConfig.get().wallMaxVertices);
	}

	private static int[] widenedHull(List<long[]> points, int margin, int maxVertices) {
		List<long[]> widened = new ArrayList<>();
		for (long[] p : points) {
			for (int k = 0; k < WIDEN_CORNERS; k++) {
				double angle = Math.PI * 2 * k / WIDEN_CORNERS;
				// Slightly beyond the margin so the octagon's flat sides still keep the full margin.
				double r = margin / Math.cos(Math.PI / WIDEN_CORNERS);
				widened.add(new long[] {Math.round(p[0] + Math.cos(angle) * r), Math.round(p[1] + Math.sin(angle) * r)});
			}
		}
		List<long[]> hull = hull(widened);
		simplify(hull, maxVertices);
		int[] polygon = new int[hull.size() * 2];
		for (int i = 0; i < hull.size(); i++) {
			polygon[i * 2] = (int) hull.get(i)[0];
			polygon[i * 2 + 1] = (int) hull.get(i)[1];
		}
		return polygon;
	}

	private static int[] circle(int cx, int cz, int radius) {
		int[] polygon = new int[CIRCLE_CORNERS * 2];
		for (int k = 0; k < CIRCLE_CORNERS; k++) {
			double angle = Math.PI * 2 * k / CIRCLE_CORNERS;
			polygon[k * 2] = cx + (int) Math.round(Math.cos(angle) * radius);
			polygon[k * 2 + 1] = cz + (int) Math.round(Math.sin(angle) * radius);
		}
		return polygon;
	}

	/** Andrew's monotone chain; counter-clockwise, no collinear points. */
	private static List<long[]> hull(List<long[]> points) {
		points.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
		int n = points.size();
		long[][] h = new long[2 * n][];
		int k = 0;
		for (int i = 0; i < n; i++) {
			while (k >= 2 && cross(h[k - 2], h[k - 1], points.get(i)) <= 0) {
				k--;
			}
			h[k++] = points.get(i);
		}
		for (int i = n - 2, t = k + 1; i >= 0; i--) {
			while (k >= t && cross(h[k - 2], h[k - 1], points.get(i)) <= 0) {
				k--;
			}
			h[k++] = points.get(i);
		}
		List<long[]> result = new ArrayList<>();
		for (int i = 0; i < k - 1; i++) {
			result.add(h[i]);
		}
		return result;
	}

	private static long cross(long[] o, long[] a, long[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	/** Drops the corners that cut the least area until at most {@code maxVertices} are left. */
	private static void simplify(List<long[]> polygon, int maxVertices) {
		while (polygon.size() > maxVertices) {
			int best = -1;
			long bestArea = Long.MAX_VALUE;
			int n = polygon.size();
			for (int i = 0; i < n; i++) {
				long area = Math.abs(cross(polygon.get((i + n - 1) % n), polygon.get(i), polygon.get((i + 1) % n)));
				if (area < bestArea) {
					bestArea = area;
					best = i;
				}
			}
			polygon.remove(best);
		}
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
