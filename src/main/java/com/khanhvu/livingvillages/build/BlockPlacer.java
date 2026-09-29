package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.config.LVConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes build steps. Only ever writes into air or replaceable blocks (plus natural ground being levelled
 * inside the house volume), so player builds and other mods' blocks are never overwritten: such blocks are
 * skipped and counted instead.
 * <p>
 * Blocks are set without neighbour updates so half-built parts do not pop off; {@link #flushUpdates()} then
 * applies shape and neighbour updates for everything placed since the last flush, like vanilla structure placement.
 */
public class BlockPlacer {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private final ServerLevel level;
	private final boolean playSounds;
	private final List<BlockPos> pendingUpdates = new ArrayList<>();
	private int placedCount;
	private int skippedCount;
	/** Saplings to replant for trees felled since the last {@link #drainFelledSaplings()}. */
	private final List<ResourceLocation> felledSaplings = new ArrayList<>();

	public BlockPlacer(ServerLevel level, boolean playSounds) {
		this.level = level;
		this.playSounds = playSounds;
	}

	public int getPlacedCount() {
		return placedCount;
	}

	/** Template blocks that could not be placed because something else was in the way. */
	public int getSkippedCount() {
		return skippedCount;
	}

	/** Saplings for the trees felled since the last call, then forgets them. */
	public List<ResourceLocation> drainFelledSaplings() {
		List<ResourceLocation> result = List.copyOf(felledSaplings);
		felledSaplings.clear();
		return result;
	}

	public boolean isLoaded(BuildStep step) {
		return level.isLoaded(step.anchor());
	}

	/** Runs one step and returns how many blocks it changed; 0 means there was nothing to do (or it was skipped). */
	public int execute(BuildStep step) {
		return switch (step) {
			case BuildStep.ChopTree t -> chopTree(t);
			case BuildStep.Foundation f -> foundation(f);
			case BuildStep.Clear c -> clear(c);
			case BuildStep.Place p -> place(p);
		};
	}

	/** Shape and neighbour updates for blocks placed since the last flush (end of a layer or of the project). */
	public void flushUpdates() {
		for (BlockPos pos : pendingUpdates) {
			BlockState state = level.getBlockState(pos);
			BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
			if (updated != state) {
				level.setBlock(pos, updated, FLAGS);
			}
			level.blockUpdated(pos, updated.getBlock());
		}
		pendingUpdates.clear();
	}

	/**
	 * Fells the tree again found from its root: it may have changed or be gone since the site was chosen, and a
	 * player may have made it non-natural (then it is left alone). Blocks are removed top first with no drops.
	 */
	private int chopTree(BuildStep.ChopTree step) {
		TreeFeller.Tree tree = TreeFeller.findTree(level, step.root(), LVConfig.get().maxTreeLogs);
		if (tree == null) {
			return 0;
		}
		felledSaplings.add(TreeFeller.saplingFor(level.getBlockState(tree.root())));
		int changed = 0;
		for (BlockPos pos : TreeFeller.collectFelling(level, tree)) {
			if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)) {
				pendingUpdates.add(pos);
				changed++;
			}
		}
		if (changed > 0 && playSounds) {
			level.playSound(null, tree.root(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8F, 0.9F);
		}
		return changed;
	}

	private int foundation(BuildStep.Foundation step) {
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = step.topY(); y > step.topY() - BuildOrder.MAX_FOUNDATION_DEPTH; y--) {
			pos.set(step.x(), y, step.z());
			if (!canPlaceInto(level.getBlockState(pos))) {
				break; // reached the ground
			}
			if (set(pos.immutable(), step.state())) {
				changed++;
			}
		}
		return changed;
	}

	private int clear(BuildStep.Clear step) {
		int changed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = step.fromY(); y <= step.toY(); y++) {
			pos.set(step.x(), y, step.z());
			BlockState state = level.getBlockState(pos);
			boolean naturalLeaves = LVConfig.get().allowTreeClearing && TreeFeller.isNaturalLeaves(state);
			if (!state.isAir() && (SiteFinder.isClearable(state) || SiteFinder.isNaturalGround(state) || naturalLeaves)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
				pendingUpdates.add(pos.immutable());
				changed++;
			}
		}
		return changed;
	}

	private int place(BuildStep.Place step) {
		for (BuildStep.Placement placement : step.blocks()) {
			BlockState current = level.getBlockState(placement.pos());
			if (current != placement.state() && !canPlaceInto(current)) {
				// Both halves or neither. Ground under the floor is expected to be there already.
				if (!step.belowFloor()) {
					skippedCount += step.blocks().size();
				}
				return 0;
			}
		}
		int changed = 0;
		for (BuildStep.Placement placement : step.blocks()) {
			if (level.getBlockState(placement.pos()) == placement.state()) {
				continue;
			}
			if (set(placement.pos(), placement.state())) {
				changed++;
				if (placement.nbt() != null) {
					loadBlockEntity(placement);
				}
			}
		}
		if (changed > 0 && playSounds) {
			BuildStep.Placement first = step.blocks().get(0);
			SoundType sound = first.state().getSoundType();
			level.playSound(null, first.pos(), sound.getPlaceSound(), SoundSource.BLOCKS,
					(sound.getVolume() + 1.0F) / 4.0F, sound.getPitch() * 0.8F);
		}
		return changed;
	}

	private boolean set(BlockPos pos, BlockState state) {
		if (level.setBlock(pos, state, FLAGS)) {
			placedCount++;
			pendingUpdates.add(pos);
			return true;
		}
		return false;
	}

	private void loadBlockEntity(BuildStep.Placement placement) {
		BlockEntity blockEntity = level.getBlockEntity(placement.pos());
		if (blockEntity == null) {
			return;
		}
		CompoundTag nbt = placement.nbt().copy();
		// No free loot in built houses.
		nbt.remove("LootTable");
		nbt.remove("LootTableSeed");
		blockEntity.loadWithComponents(nbt, level.registryAccess());
		blockEntity.setChanged();
	}

	private static boolean canPlaceInto(BlockState state) {
		return state.isAir() || state.canBeReplaced();
	}
}
