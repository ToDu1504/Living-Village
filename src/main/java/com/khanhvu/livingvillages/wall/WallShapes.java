package com.khanhvu.livingvillages.wall;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks walls are made of (config {@code wallBlocks}) and the shape of a watch tower (spec v3-GĐ 2). Pure
 * geometry: nothing here reads the world.
 */
public final class WallShapes {
	/** Tower floor height above its base: taller than the highest city wall by three blocks. */
	public static int towerHeight() {
		return LVConfig.get().cityWallHeightMax + 3;
	}

	/** Interior cells around the central pillar, in climbing order, as (u, v); the door leads into the first. */
	private static final int[][] SPIRAL = {{1, 0}, {1, 1}, {2, 1}, {3, 1}, {3, 0}, {3, -1}, {2, -1}, {1, -1}};

	/** One block of a shape: {@code state} null means the position must be left empty. */
	public record Piece(BlockPos pos, @Nullable BlockState state) {
	}

	/** The wall blocks of a village type, resolved. */
	public record Blocks4(Block main, Block top, Block palisade, Block foundation, Block stairs) {
	}

	private WallShapes() {
	}

	public static Blocks4 blocks(VillageRecord village) {
		LVConfig.WallBlockSet set = LVConfig.get().wallBlocks.get(VillageTicker.villageType(village).getSerializedName());
		Block main = block(set == null ? null : set.main, Blocks.STONE_BRICKS);
		return new Blocks4(main, block(set == null ? null : set.top, Blocks.STONE_BRICK_WALL),
				block(set == null ? null : set.palisade, Blocks.OAK_FENCE), block(set == null ? null : set.foundation, Blocks.COBBLESTONE),
				stairsFor(main));
	}

	private static Block block(@Nullable String id, Block fallback) {
		ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id);
		return location == null ? fallback : BuiltInRegistries.BLOCK.getOptional(location).orElse(fallback);
	}

	/** Stairs of the main block's material: stone_bricks → stone_brick_stairs, cut_sandstone → sandstone_stairs. */
	private static Block stairsFor(Block main) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(main);
		String path = id.getPath();
		List<String> candidates = List.of(path + "_stairs", path.replaceAll("s$", "") + "_stairs",
				path.replace("cut_", "").replace("chiseled_", "").replace("smooth_", "") + "_stairs");
		for (String candidate : candidates) {
			var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), candidate));
			if (block.isPresent() && block.get() instanceof StairBlock) {
				return block.get();
			}
		}
		return Blocks.COBBLESTONE_STAIRS;
	}

	/** World position of tower-local (u out of the village, v sideways, dy up from the base). */
	public static BlockPos at(WallRing.Tower tower, int u, int v, int dy) {
		Direction side = tower.out.getClockWise();
		return new BlockPos(tower.x + tower.out.getStepX() * u + side.getStepX() * v, tower.baseY + dy,
				tower.z + tower.out.getStepZ() * u + side.getStepZ() * v);
	}

	/** Every (u, v) of the tower square. */
	public static List<int[]> footprint() {
		List<int[]> cells = new ArrayList<>();
		for (int u = 0; u <= 4; u++) {
			for (int v = -2; v <= 2; v++) {
				cells.add(new int[] {u, v});
			}
		}
		return cells;
	}

	/**
	 * The tower above its base, bottom up: outer walls with a door (1 wide, 2 high) in the middle of the inner face,
	 * a central pillar, a spiral stair around it up to the top floor, the top floor with the openings the stair
	 * needs for head room, and a railing of low wall blocks. Foundations below the base are added by the builder,
	 * which knows the ground.
	 */
	public static List<Piece> tower(WallRing.Tower tower, Blocks4 blocks) {
		int height = towerHeight();
		List<Piece> pieces = new ArrayList<>();
		// Which spiral step sits in which cell, and which top-floor cells stay open.
		int[][] stairAt = new int[5][5];
		for (int[] row : stairAt) {
			java.util.Arrays.fill(row, -1);
		}
		for (int k = 0; k < height; k++) {
			int[] cell = SPIRAL[(k + 1) % SPIRAL.length];
			stairAt[cell[0]][cell[1] + 2] = k;
		}
		for (int dy = 1; dy <= height + 1; dy++) {
			for (int[] cell : footprint()) {
				int u = cell[0];
				int v = cell[1];
				boolean perimeter = u == 0 || u == 4 || v == -2 || v == 2;
				BlockPos pos = at(tower, u, v, dy);
				if (dy == height + 1) {
					pieces.add(new Piece(pos, perimeter ? blocks.top().defaultBlockState() : null));
					continue;
				}
				if (perimeter) {
					boolean door = u == 0 && v == 0 && dy <= 2;
					pieces.add(new Piece(pos, door ? null : blocks.main().defaultBlockState()));
					continue;
				}
				int step = stairAt[u][v + 2];
				if (step >= 0 && dy == step + 1) {
					pieces.add(new Piece(pos, stair(tower, blocks, step)));
				} else if (u == 2 && v == 0) {
					pieces.add(new Piece(pos, blocks.main().defaultBlockState())); // pillar, then floor
				} else if (dy == height) {
					// Top floor, open over the last steps below it: a villager jumping from one step to the next needs
					// three free blocks above the step it jumps from.
					boolean open = step >= height - 4 && step <= height - 2;
					pieces.add(new Piece(pos, open ? null : blocks.main().defaultBlockState()));
				} else {
					pieces.add(new Piece(pos, null));
				}
			}
		}
		return pieces;
	}

	/** The stair of step k, rising towards the next step. */
	private static BlockState stair(WallRing.Tower tower, Blocks4 blocks, int k) {
		int[] from = SPIRAL[(k + 1) % SPIRAL.length];
		int[] to = SPIRAL[(k + 2) % SPIRAL.length];
		Direction side = tower.out.getClockWise();
		int du = to[0] - from[0];
		int dv = to[1] - from[1];
		Direction facing = du > 0 ? tower.out : du < 0 ? tower.out.getOpposite() : dv > 0 ? side : side.getOpposite();
		return blocks.stairs().defaultBlockState().setValue(StairBlock.FACING, facing);
	}
}
