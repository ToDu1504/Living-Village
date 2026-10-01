package com.khanhvu.livingvillages.wall;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walls of one village (spec v3), saved with the village: its rings, every block the mod placed for them, and
 * the blocks players broke (never placed again).
 */
public final class VillageWalls {
	private static final int COLUMN_BITS = 20;
	private static final int COLUMN_MASK = (1 << COLUMN_BITS) - 1;
	/** Highest ring id that fits; kept for garden fences, real rings count up from 1 (0 is torches). */
	public static final int MAX_RING_ID = (1 << (32 - COLUMN_BITS)) - 1;

	/** Rings from the innermost to the outermost. */
	private final List<WallRing> rings = new ArrayList<>();
	/** An old palisade taken down once the palisade that replaces it stands (spec v3-GĐ 1). */
	@Nullable
	private WallRing retiring;
	/** Block position → ring id and column of every block the mod placed for the walls. */
	private final Long2IntOpenHashMap blocks = new Long2IntOpenHashMap();
	/** Positions of wall blocks a player broke: they stay open. */
	private final LongOpenHashSet abandoned = new LongOpenHashSet();
	private int nextRingId = 1;
	/** Houses whose garden fence is finished (v3-GĐ 6). */
	private final List<BoundingBox> fencedPlots = new ArrayList<>();
	/** Set by the walls off command. */
	private boolean paused;

	public List<WallRing> getRings() {
		return rings;
	}

	@Nullable
	public WallRing outer() {
		return rings.isEmpty() ? null : rings.get(rings.size() - 1);
	}

	@Nullable
	public WallRing getRetiring() {
		return retiring;
	}

	public void setRetiring(@Nullable WallRing retiring) {
		this.retiring = retiring;
	}

	@Nullable
	public WallRing ring(int id) {
		for (WallRing ring : rings) {
			if (ring.getId() == id) {
				return ring;
			}
		}
		return retiring != null && retiring.getId() == id ? retiring : null;
	}

	public int takeRingId() {
		return nextRingId++;
	}

	public Long2IntOpenHashMap getBlocks() {
		return blocks;
	}

	public void addBlock(long pos, int ringId, int column) {
		blocks.put(pos, ringId << COLUMN_BITS | column);
	}

	public static int ringOf(int value) {
		return value >>> COLUMN_BITS;
	}

	public static int columnOf(int value) {
		return value & COLUMN_MASK;
	}

	public List<BoundingBox> getFencedPlots() {
		return fencedPlots;
	}

	public LongOpenHashSet getAbandoned() {
		return abandoned;
	}

	public boolean isPaused() {
		return paused;
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		ListTag ringList = new ListTag();
		for (WallRing ring : rings) {
			ringList.add(ring.save());
		}
		tag.put("Rings", ringList);
		if (retiring != null) {
			tag.put("Retiring", retiring.save());
		}
		long[] positions = new long[blocks.size()];
		int[] values = new int[blocks.size()];
		int i = 0;
		for (Long2IntMap.Entry entry : blocks.long2IntEntrySet()) {
			positions[i] = entry.getLongKey();
			values[i] = entry.getIntValue();
			i++;
		}
		tag.putLongArray("Blocks", positions);
		tag.putIntArray("BlockRings", values);
		tag.putLongArray("Abandoned", abandoned.toLongArray());
		tag.putInt("NextRingId", nextRingId);
		tag.putBoolean("Paused", paused);
		ListTag fenced = new ListTag();
		for (BoundingBox box : fencedPlots) {
			fenced.add(new IntArrayTag(new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}));
		}
		tag.put("FencedPlots", fenced);
		return tag;
	}

	public static VillageWalls load(CompoundTag tag) {
		VillageWalls walls = new VillageWalls();
		ListTag ringList = tag.getList("Rings", Tag.TAG_COMPOUND);
		for (int i = 0; i < ringList.size(); i++) {
			WallRing ring = WallRing.load(ringList.getCompound(i));
			if (ring != null) {
				walls.rings.add(ring);
			}
		}
		if (tag.contains("Retiring", Tag.TAG_COMPOUND)) {
			walls.retiring = WallRing.load(tag.getCompound("Retiring"));
		}
		long[] positions = tag.getLongArray("Blocks");
		int[] values = tag.getIntArray("BlockRings");
		for (int i = 0; i < Math.min(positions.length, values.length); i++) {
			walls.blocks.put(positions[i], values[i]);
		}
		walls.abandoned.addAll(LongArrayList.wrap(tag.getLongArray("Abandoned")));
		walls.nextRingId = Math.max(1, tag.getInt("NextRingId"));
		walls.paused = tag.getBoolean("Paused");
		ListTag fenced = tag.getList("FencedPlots", Tag.TAG_INT_ARRAY);
		for (int i = 0; i < fenced.size(); i++) {
			int[] a = fenced.getIntArray(i);
			if (a.length == 6) {
				walls.fencedPlots.add(new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]));
			}
		}
		return walls;
	}
}
