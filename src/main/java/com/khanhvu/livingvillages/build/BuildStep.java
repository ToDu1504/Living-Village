package com.khanhvu.livingvillages.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One unit of building work. Steps are pure data derived from template + origin + rotation; what they actually
 * change is decided against the world when they run, so a saved step index stays valid after a reload.
 */
public sealed interface BuildStep {
	/** A position inside the step, used to check that its chunk is loaded. */
	BlockPos anchor();

	/** Fell the natural tree whose lowest log is {@code root}: logs, its leaves, vines; nothing drops. */
	record ChopTree(BlockPos root) implements BuildStep {
		@Override
		public BlockPos anchor() {
			return root;
		}
	}

	/** Fill a column with foundation blocks from {@code topY} down until solid ground. */
	record Foundation(int x, int z, int topY, BlockState state) implements BuildStep {
		@Override
		public BlockPos anchor() {
			return new BlockPos(x, topY, z);
		}
	}

	/** Remove plants, snow layers and natural ground from {@code fromY} to {@code toY} in one column. */
	record Clear(int x, int z, int fromY, int toY) implements BuildStep {
		@Override
		public BlockPos anchor() {
			return new BlockPos(x, fromY, z);
		}
	}

	/**
	 * Place one template block, or both halves of a bed, door or tall plant together so neither half breaks.
	 *
	 * @param belowFloor the blocks sit under the house floor (e.g. a grass layer) where the ground is expected;
	 *                   finding them occupied is normal, not a skip
	 */
	record Place(List<Placement> blocks, boolean belowFloor) implements BuildStep {
		@Override
		public BlockPos anchor() {
			return blocks.get(0).pos();
		}
	}

	record Placement(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
	}
}
