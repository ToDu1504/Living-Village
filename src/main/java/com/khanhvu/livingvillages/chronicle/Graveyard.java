package com.khanhvu.livingvillages.chronicle;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Optional;

/**
 * Graves for named villagers and guards (spec v2-GĐ 7.4): a 7×7 graveyard near the edge of the village, chosen at
 * the first death, with a stone wall block and a sign per grave on a fixed grid. A full graveyard only gets
 * chronicle entries. Only air or replaceable blocks are ever replaced.
 */
public final class Graveyard {
	public static final int SIZE = 7;
	/** Graves per row (x = 0, 2, 4, 6) and rows (the wall at z = 1 or 4, the sign south of it). */
	private static final int COLUMNS = 4;
	private static final int[] ROW_Z = {1, 4};
	public static final int CAPACITY = COLUMNS * ROW_Z.length;
	private static final int CLEAR_HEIGHT = 2;

	private Graveyard() {
	}

	/** Adds a grave; returns false when there is no graveyard site, it is full, or the spot is blocked. */
	public static boolean addGrave(ServerLevel level, VillageRecord village, String name, Component role, long day) {
		BoundingBox yard = village.getGraveyard();
		if (yard == null) {
			yard = chooseSite(level, village);
			if (yard == null) {
				return false;
			}
			village.setGraveyard(yard);
			village.getPlots().add(yard); // houses and other graveyards keep off it
		}
		int index = village.getGraveCount();
		if (index >= CAPACITY) {
			return false;
		}
		int x = yard.minX() + (index % COLUMNS) * 2;
		int z = yard.minZ() + ROW_Z[index / COLUMNS];
		BlockPos wall = surface(level, x, z);
		BlockPos sign = surface(level, x, z + 1);
		if (wall == null || sign == null) {
			return false;
		}
		village.setGraveCount(index + 1); // the slot is used even if a player blocked it, so graves never pile up
		if (!SiteFinder.isClearable(level.getBlockState(wall)) || !SiteFinder.isClearable(level.getBlockState(sign))) {
			return false;
		}
		level.setBlockAndUpdate(wall, wallBlock(village).defaultBlockState());
		BlockState signState = signBlock(village).defaultBlockState().setValue(StandingSignBlock.ROTATION, 0); // text faces south
		level.setBlockAndUpdate(sign, signState);
		if (level.getBlockEntity(sign) instanceof SignBlockEntity entity) {
			SignText text = new SignText()
					.setMessage(0, Component.literal(name))
					.setMessage(1, role)
					.setMessage(2, LVText.tr("livingvillages.grave.day", day));
			entity.setText(text, true);
			entity.setWaxed(true); // players cannot edit the text
		}
		VillageRegistry.get(level).setDirty();
		LivingVillages.debug("Village {}: grave {} for {} at {}", village.getId(), index, name, wall);
		return true;
	}

	/** Near the edge of the building radius, where a village keeps its graveyard. */
	private static BoundingBox chooseSite(ServerLevel level, VillageRecord village) {
		int radius = VillageLevel.buildRadius(village);
		Optional<BoundingBox> site = SiteFinder.findFlatArea(level, village, SIZE, CLEAR_HEIGHT,
				Math.max(12, radius * 2 / 3), radius, level.getRandom());
		return site.orElse(null);
	}

	/** First free block above the ground of a column, or null when its chunk is not loaded. */
	private static BlockPos surface(ServerLevel level, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		return new BlockPos(x, SiteFinder.groundTop(level, x, z) + 1, z);
	}

	private static Block wallBlock(VillageRecord village) {
		return switch (VillageTicker.villageType(village)) {
			case DESERT -> Blocks.SANDSTONE_WALL;
			case TAIGA -> Blocks.MOSSY_COBBLESTONE_WALL;
			case SNOWY -> Blocks.STONE_BRICK_WALL;
			default -> Blocks.COBBLESTONE_WALL;
		};
	}

	/** Sign wood that fits the village style (also used for the material board). */
	public static Block signBlock(VillageRecord village) {
		return switch (VillageTicker.villageType(village)) {
			case DESERT -> Blocks.BIRCH_SIGN;
			case SAVANNA -> Blocks.ACACIA_SIGN;
			case SNOWY, TAIGA -> Blocks.SPRUCE_SIGN;
			default -> Blocks.OAK_SIGN;
		};
	}
}
