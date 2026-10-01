package com.khanhvu.livingvillages.care;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.wall.VillageBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * The coarse pass that makes the inside of a village's frame buildable (spec v4 §8.1): pits, caves, water and small
 * cliffs go, but the lie of the land stays, because the target height field only limits how much two neighbouring
 * columns may differ -- it is never levelled to one height. Fill is earth topped with the surface block of the area,
 * and a column that is cut gets its surface block back, so a graded city does not read as bare stone.
 *
 * <p>A column needing more than {@code maxGradeCut} (a ravine, a big cave) is left alone; the site checks reject it
 * later, which is the honest outcome.
 */
public final class TerrainGrader {
	/** Columns looked at per run when hunting for the surface block of the area. */
	private static final int SURFACE_SEARCH = 4;
	/**
	 * Columns a tick at most. Earthwork is paced by the shared {@code maxBlocksPerTickGlobal} budget, not by counting
	 * masons: a village-wide job of several thousand columns cannot wait for a villager to walk to each one, and an
	 * entity query every tick for every village would cost more than the pacing is worth.
	 */
	private static final int COLUMNS_PER_TICK = 16;
	private static final int MAX_WATER_CLEAR = 24;

	private TerrainGrader() {
	}

	public static boolean enabled() {
		LVConfig config = LVConfig.get();
		return config.levelTerrain && config.gradeTerrain;
	}

	/** Called every tick for villages near a player, after the walls. */
	public static void tick(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		if (!enabled() || village.getFrame() == null) {
			return;
		}
		VillageGrading grading = village.getGrading();
		if (grading != null && !grading.matches(village.getFrame())) {
			grading = null; // the frame changed under it (an older world): plan again
		}
		if (grading == null) {
			grading = plan(level, village);
			if (grading == null) {
				return;
			}
			village.setGrading(grading);
			registry.setDirty();
		}
		if (grading.done() || !VillageTicker.isWorkTime(level) || level.getRaidAt(village.getBellPos()) != null) {
			return;
		}
		int worked = 0;
		for (int i = 0; i < COLUMNS_PER_TICK && !grading.done() && VillageTicker.hasBudget(level); i++) {
			// The cursor only moves on a column that is finished, so a column cut short by the budget is retried.
			if (!gradeColumn(level, village, grading, grading.cursor())) {
				break;
			}
			grading.advance();
			worked++;
		}
		if (worked == 0) {
			return;
		}
		registry.setDirty();
		if (grading.done()) {
			LivingVillages.debug("Village {}: ground graded, {} columns", village.getId(), grading.columns());
		}
	}

	/**
	 * The target height of every column inside the frame: the ground as it is (the water surface where there is water,
	 * the floor of a pit where there is one), smoothed until no two neighbours differ by more than {@code maxSlope}.
	 * Null while any chunk of the frame is unloaded, so the field is never built from a half-seen world.
	 */
	@Nullable
	private static VillageGrading plan(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		int[] frame = village.getFrame();
		if (!VillageBoundary.areaLoaded(level, village.getBellPos(), frameRadius(frame, village.getBellPos()))) {
			return null;
		}
		// The wall stands on the frame's edge: grading that line would bury or undermine it, and the repair check would
		// then keep reopening the columns. Grading starts inside the wall and its free strip.
		int inset = 1 + config.wallInnerBuffer;
		int minX = frame[0] + inset;
		int minZ = frame[1] + inset;
		int width = frame[2] - inset - minX + 1;
		int depth = frame[3] - inset - minZ + 1;
		if (width <= 0 || depth <= 0) {
			return null;
		}
		int[] h = new int[width * depth];
		long sum = 0;
		for (int i = 0; i < h.length; i++) {
			int x = minX + i / depth;
			int z = minZ + i % depth;
			h[i] = SiteFinder.groundTop(level, x, z);
			sum += h[i];
		}
		// Gauss-Seidel in a fixed order, a fixed number of rounds: it always ends and always gives the same field.
		for (int pass = 0; pass < config.gradingPasses; pass++) {
			for (int i = 0; i < h.length; i++) {
				int lo = Integer.MIN_VALUE;
				int hi = Integer.MAX_VALUE;
				int neighbours = 0;
				int total = 0;
				for (int n : neighboursOf(i, width, depth)) {
					lo = Math.max(lo, h[n] - config.maxSlope);
					hi = Math.min(hi, h[n] + config.maxSlope);
					total += h[n];
					neighbours++;
				}
				if (neighbours == 0) {
					continue;
				}
				h[i] = lo > hi ? Math.round((float) total / neighbours) : Math.clamp(h[i], lo, hi);
			}
		}
		int baseY = (int) (sum / h.length);
		byte[] offsets = new byte[h.length];
		int unset = 0;
		for (int i = 0; i < h.length; i++) {
			int delta = h[i] - baseY;
			if (delta < Byte.MIN_VALUE + 1 || delta > Byte.MAX_VALUE) {
				offsets[i] = VillageGrading.UNSET;
				unset++;
			} else {
				offsets[i] = (byte) delta;
			}
		}
		LivingVillages.debug("Village {}: grading planned, {}x{} columns inset {}, base y={}, {} out of range",
				village.getId(), width, depth, inset, baseY, unset);
		return new VillageGrading(minX, minZ, width, depth, baseY, offsets, 0);
	}

	private static int[] neighboursOf(int i, int width, int depth) {
		int x = i / depth;
		int z = i % depth;
		int count = 0;
		int[] out = new int[4];
		if (x > 0) {
			out[count++] = i - depth;
		}
		if (x < width - 1) {
			out[count++] = i + depth;
		}
		if (z > 0) {
			out[count++] = i - 1;
		}
		if (z < depth - 1) {
			out[count++] = i + 1;
		}
		return java.util.Arrays.copyOf(out, count);
	}

	/** Half the frame's longest side from the bell, so the loaded check covers the whole frame. */
	private static int frameRadius(int[] frame, BlockPos bell) {
		return Math.max(Math.max(bell.getX() - frame[0], frame[2] - bell.getX()),
				Math.max(bell.getZ() - frame[1], frame[3] - bell.getZ()));
	}

	/** Digs or fills one column to its target, leaving the surface block of the area on top; false if unfinished. */
	private static boolean gradeColumn(ServerLevel level, VillageRecord village, VillageGrading grading, int index) {
		int target = grading.target(index);
		if (target == Integer.MIN_VALUE) {
			return true;
		}
		return levelColumn(level, village, grading.xOf(index), grading.zOf(index), target, LVConfig.get().maxGradeCut);
	}

	/**
	 * Moves one column of ground to {@code target}, at most {@code maxMove} blocks, and leaves the surface block of the
	 * area on top. Only natural ground and water are touched: anything built, by a player or by the mod, stops the
	 * column where it is (spec v4 §2). False only when the per-tick block budget ran out part way, so the caller can
	 * come back to the same column.
	 */
	public static boolean levelColumn(ServerLevel level, VillageRecord village, int x, int z, int target, int maxMove) {
		LVConfig config = LVConfig.get();
		if (!level.hasChunk(x >> 4, z >> 4) || insideSomePlot(level, x, z)) {
			return true;
		}
		if (village.getWalls().getBlocks().containsKey(new BlockPos(x, target, z).asLong())) {
			return true; // a wall block or a torch stands here
		}
		int ground = SiteFinder.groundTop(level, x, z);
		if (ground == target || Math.abs(target - ground) > maxMove) {
			return true;
		}
		BlockState top = level.getBlockState(new BlockPos(x, ground, z));
		boolean water = !top.getFluidState().isEmpty();
		if (water && !config.fillWater) {
			return true;
		}
		if (!water && !SiteFinder.isNaturalGround(top) && !SiteFinder.isClearable(top)) {
			return true;
		}
		Block surface = surfaceBlock(level, x, z, ground);
		Block filler = fillerFor(surface);
		if (target > ground) {
			for (int y = ground + (water ? 0 : 1); y < target; y++) {
				if (!replaceable(level, x, y, z)) {
					return true;
				}
				if (!VillageTicker.useBudget(level)) {
					return false;
				}
				level.setBlockAndUpdate(new BlockPos(x, y, z), filler.defaultBlockState());
			}
		} else {
			for (int y = ground; y > target; y--) {
				BlockState state = level.getBlockState(new BlockPos(x, y, z));
				if (state.getFluidState().isEmpty() && !SiteFinder.isNaturalGround(state) && !SiteFinder.isClearable(state)) {
					return true;
				}
				if (!VillageTicker.useBudget(level)) {
					return false;
				}
				level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		if (!VillageTicker.useBudget(level)) {
			return false;
		}
		level.setBlockAndUpdate(new BlockPos(x, target, z), surface.defaultBlockState());
		// Water that ran in from the side while the column was being filled.
		for (int y = target + 1; y <= target + MAX_WATER_CLEAR; y++) {
			BlockState state = level.getBlockState(new BlockPos(x, y, z));
			if (state.getFluidState().isEmpty()) {
				break;
			}
			level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
		}
		return true;
	}

	private static boolean replaceable(ServerLevel level, int x, int y, int z) {
		BlockState state = level.getBlockState(new BlockPos(x, y, z));
		return SiteFinder.isClearable(state) || SiteFinder.isNaturalGround(state) || !state.getFluidState().isEmpty();
	}

	/** Plots of every village: a graded column must not disturb a building's ground. */
	private static boolean insideSomePlot(ServerLevel level, int x, int z) {
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot.isInside(x, plot.minY(), z)) {
					return true;
				}
			}
			if (other.getProject() != null && other.getProject().getFootprint().isInside(x, other.getProject().getFootprint().minY(), z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The block the area wears on top: the column's own if it is a surface, otherwise the nearest neighbour's. Keeping
	 * this right is what stops a graded city looking like a quarry.
	 */
	private static Block surfaceBlock(ServerLevel level, int x, int z, int ground) {
		BlockState own = level.getBlockState(new BlockPos(x, ground, z));
		if (isSurface(own)) {
			return own.getBlock();
		}
		for (int r = 1; r <= SURFACE_SEARCH; r++) {
			for (int[] d : new int[][] {{r, 0}, {-r, 0}, {0, r}, {0, -r}}) {
				int nx = x + d[0];
				int nz = z + d[1];
				if (!level.hasChunk(nx >> 4, nz >> 4)) {
					continue;
				}
				BlockState state = level.getBlockState(new BlockPos(nx, SiteFinder.groundTop(level, nx, nz), nz));
				if (isSurface(state)) {
					return state.getBlock();
				}
			}
		}
		return Blocks.DIRT;
	}

	private static boolean isSurface(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.MYCELIUM)
				|| state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK) || state.is(BlockTags.TERRACOTTA);
	}

	/** What goes under the surface block: sand keeps sand, everything else is earth. */
	private static Block fillerFor(Block surface) {
		BlockState state = surface.defaultBlockState();
		if (state.is(BlockTags.SAND)) {
			return surface;
		}
		if (state.is(BlockTags.TERRACOTTA) || state.is(Blocks.GRAVEL)) {
			return surface;
		}
		return Blocks.DIRT;
	}
}
