package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds and fells natural trees. A tree is natural when its logs touch at least one leaf block with
 * {@code persistent=false}: leaves placed by players are always persistent, so log houses and decorated player
 * trees never qualify. Trees with a bee nest are left alone (felling would kill the bees).
 */
public final class TreeFeller {
	/** Natural leaves decay beyond this distance from a log, so no leaf of the tree can be farther. */
	private static final int MAX_LEAF_DISTANCE = 6;
	/** Safety bound on blocks removed with one tree (logs + leaves + vines). */
	private static final int MAX_FELLED_BLOCKS = 2048;

	/** A natural tree: its lowest log (the root) and all its logs. */
	public record Tree(BlockPos root, Set<BlockPos> logs) {
	}

	private TreeFeller() {
	}

	/** Logs and wood of trees; stripped variants only come from players. */
	public static boolean isTreeLog(BlockState state) {
		return state.is(BlockTags.LOGS) && !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
	}

	public static boolean isNaturalLeaves(BlockState state) {
		return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	/**
	 * The natural tree containing the log at {@code start}, or null if it is not one: not a log, bigger than
	 * {@code maxLogs}, no natural leaves, has a bee nest, or reaches into an unloaded chunk.
	 */
	@Nullable
	public static Tree findTree(ServerLevel level, BlockPos start, int maxLogs) {
		if (!level.isLoaded(start) || !isTreeLog(level.getBlockState(start))) {
			return null;
		}
		Set<BlockPos> logs = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		logs.add(start.immutable());
		queue.add(start.immutable());
		boolean natural = false;
		while (!queue.isEmpty()) {
			BlockPos log = queue.poll();
			// 26-neighbourhood: acacia and dark oak branches touch diagonally.
			for (BlockPos next : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				if (logs.contains(next)) {
					continue;
				}
				if (!level.isLoaded(next)) {
					return null;
				}
				BlockState state = level.getBlockState(next);
				if (isTreeLog(state)) {
					BlockPos found = next.immutable();
					logs.add(found);
					if (logs.size() > maxLogs) {
						return null; // giant tree: stays an obstacle
					}
					queue.add(found);
				}
			}
			for (Direction direction : Direction.values()) {
				BlockState side = level.getBlockState(log.relative(direction));
				if (side.getBlock() instanceof BeehiveBlock) {
					return null;
				}
				if (isNaturalLeaves(side)) {
					natural = true;
				}
			}
		}
		if (!natural) {
			return null;
		}
		BlockPos root = logs.stream()
				.min(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()))
				.orElseThrow();
		return new Tree(root, logs);
	}

	/**
	 * Every block removed when {@code tree} is felled, top first: its logs, the natural leaves attached to them, and
	 * vines and cocoa hanging on those. Leaves that also touch a log of another tree belong to that tree and stay.
	 */
	public static List<BlockPos> collectFelling(ServerLevel level, Tree tree) {
		Set<BlockPos> removed = new LinkedHashSet<>(tree.logs());
		Set<BlockPos> leaves = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>(tree.logs());
		for (int step = 0; step < MAX_LEAF_DISTANCE && !frontier.isEmpty(); step++) {
			ArrayDeque<BlockPos> next = new ArrayDeque<>();
			for (BlockPos pos : frontier) {
				for (Direction direction : Direction.values()) {
					BlockPos side = pos.relative(direction);
					if (removed.size() >= MAX_FELLED_BLOCKS || leaves.contains(side) || !level.isLoaded(side)) {
						continue;
					}
					if (isNaturalLeaves(level.getBlockState(side)) && !touchesForeignLog(level, side, tree.logs())) {
						leaves.add(side);
						removed.add(side);
						next.add(side);
					}
				}
			}
			frontier = next;
		}
		// Vines and cocoa would float once their support is gone.
		List<BlockPos> attached = new ArrayList<>();
		for (BlockPos pos : removed) {
			for (Direction direction : Direction.values()) {
				BlockPos side = pos.relative(direction);
				if (!removed.contains(side) && level.isLoaded(side)) {
					Block block = level.getBlockState(side).getBlock();
					if (block instanceof VineBlock || block instanceof CocoaBlock) {
						attached.add(side);
					}
				}
			}
		}
		removed.addAll(attached);
		List<BlockPos> ordered = new ArrayList<>(removed);
		ordered.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
		return ordered;
	}

	/** Sapling to replant for a log, e.g. spruce_log → spruce_sapling; oak when there is no match. */
	public static ResourceLocation saplingFor(BlockState log) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(log.getBlock());
		String wood = id.getPath().replace("_log", "").replace("_wood", "");
		if (wood.equals("mangrove")) {
			return BuiltInRegistries.BLOCK.getKey(Blocks.MANGROVE_PROPAGULE);
		}
		ResourceLocation sapling = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), wood + "_sapling");
		return BuiltInRegistries.BLOCK.containsKey(sapling) ? sapling : BuiltInRegistries.BLOCK.getKey(Blocks.OAK_SAPLING);
	}

	private static boolean touchesForeignLog(ServerLevel level, BlockPos leaf, Set<BlockPos> ownLogs) {
		for (Direction direction : Direction.values()) {
			BlockPos side = leaf.relative(direction);
			if (!ownLogs.contains(side) && level.isLoaded(side) && isTreeLog(level.getBlockState(side))) {
				return true;
			}
		}
		return false;
	}
}
