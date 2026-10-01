package com.khanhvu.livingvillages.road;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.wall.VillageBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The planned roads of one village (spec v4 §5.2), saved with it: the four axes from the square to the gates, the
 * branches opened later, and how far along each one the masons have got. Unlike v3, where a road was a job in memory
 * that a restart threw away, the plan itself is what is kept.
 */
public final class VillageRoads {
	/** Stops the branch search even if the frame is enormous. */
	private static final int MAX_BRANCH_TRIES = 256;

	private final List<RoadSegment> segments = new ArrayList<>();
	/** Columns of each segment already worked, from its near end. A column an obstacle blocks counts as worked. */
	private final List<Integer> progress = new ArrayList<>();
	/** How many branch positions have been used up, so the next one is always a fresh line (spec v4 §7.4). */
	private int branches;

	public List<RoadSegment> getSegments() {
		return segments;
	}

	public int progress(int segment) {
		return progress.get(segment);
	}

	public void advance(int segment) {
		progress.set(segment, progress.get(segment) + 1);
	}

	public boolean done(int segment) {
		return progress.get(segment) >= segments.get(segment).length();
	}

	/** The first segment with columns left to work, or -1 when every road is finished. */
	public int unfinished() {
		for (int i = 0; i < segments.size(); i++) {
			if (!done(i)) {
				return i;
			}
		}
		return -1;
	}

	public void add(RoadSegment segment) {
		segments.add(segment);
		progress.add(0);
	}

	public int getBranches() {
		return branches;
	}

	public void countBranch() {
		branches++;
	}

	/** Whether any planned road covers the column. */
	public boolean onRoad(int x, int z) {
		for (RoadSegment segment : segments) {
			if (segment.covers(x, z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The four axes: a cross centred on the village's axis centre, each arm running from the edge of the square out to
	 * the middle of a gate, so a road always meets its gate head on. Each arm starts inside the frame even when the
	 * square reaches past the wall. Does nothing without a frame or once laid out; true when it laid them out.
	 */
	public static boolean layOutAxes(VillageRecord village) {
		VillageRoads roads = village.getRoads();
		int[] frame = village.getFrame();
		if (frame == null || !roads.segments.isEmpty()) {
			return false;
		}
		int[] c = VillageBoundary.axisCentre(frame, village.getBellPos());
		int plaza = LVConfig.get().plazaRadius;
		roads.add(RoadSegment.of(c[0], Math.max(c[1] - plaza, frame[1]), c[0], frame[1], true));
		roads.add(RoadSegment.of(c[0], Math.min(c[1] + plaza, frame[3]), c[0], frame[3], true));
		roads.add(RoadSegment.of(Math.max(c[0] - plaza, frame[0]), c[1], frame[0], c[1], true));
		roads.add(RoadSegment.of(Math.min(c[0] + plaza, frame[2]), c[1], frame[2], c[1], true));
		return true;
	}

	/**
	 * The next branch to open when the frontage along the roads runs out (spec v4 §7.4): a street parallel to one of
	 * the axes, {@code branchSpacing} further out each time, kept inside the wall and its free strip. Null when the
	 * frame has no room for another one, which is what makes a city full.
	 */
	@Nullable
	public static RoadSegment nextBranch(VillageRecord village) {
		int[] frame = village.getFrame();
		if (frame == null) {
			return null;
		}
		LVConfig config = LVConfig.get();
		int inset = 1 + config.wallInnerBuffer;
		int lowX = frame[0] + inset;
		int highX = frame[2] - inset;
		int lowZ = frame[1] + inset;
		int highZ = frame[3] - inset;
		if (lowX >= highX || lowZ >= highZ) {
			return null;
		}
		int[] c = VillageBoundary.axisCentre(frame, village.getBellPos());
		VillageRoads roads = village.getRoads();
		// Four branches per ring, each ring one branchSpacing further from the cross, so the city fills outwards.
		for (int k = roads.branches; k < MAX_BRANCH_TRIES; k++) {
			int ring = k / 4 + 1;
			int offset = ring * config.branchSpacing;
			RoadSegment branch = switch (k % 4) {
				case 0 -> c[1] - offset >= lowZ ? RoadSegment.of(lowX, c[1] - offset, highX, c[1] - offset, false) : null;
				case 1 -> c[1] + offset <= highZ ? RoadSegment.of(lowX, c[1] + offset, highX, c[1] + offset, false) : null;
				case 2 -> c[0] - offset >= lowX ? RoadSegment.of(c[0] - offset, lowZ, c[0] - offset, highZ, false) : null;
				default -> c[0] + offset <= highX ? RoadSegment.of(c[0] + offset, lowZ, c[0] + offset, highZ, false) : null;
			};
			roads.branches = k + 1; // a position that does not fit is used up, so the search always moves on
			if (branch != null) {
				return branch;
			}
			if (offset > Math.max(highX - lowX, highZ - lowZ)) {
				return null; // every ring from here on is outside the frame
			}
		}
		return null;
	}

	/** Where the cross meets, or null without a frame. */
	public static BlockPos centre(VillageRecord village) {
		int[] frame = village.getFrame();
		if (frame == null) {
			return null;
		}
		int[] c = VillageBoundary.axisCentre(frame, village.getBellPos());
		return new BlockPos(c[0], village.getBellPos().getY(), c[1]);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		ListTag list = new ListTag();
		for (int i = 0; i < segments.size(); i++) {
			list.add(new IntArrayTag(segments.get(i).toIntArray()));
		}
		tag.put("Segments", list);
		int[] done = new int[progress.size()];
		for (int i = 0; i < progress.size(); i++) {
			done[i] = progress.get(i);
		}
		tag.putIntArray("Progress", done);
		tag.putInt("Branches", branches);
		return tag;
	}

	public static VillageRoads load(CompoundTag tag) {
		VillageRoads roads = new VillageRoads();
		ListTag list = tag.getList("Segments", Tag.TAG_INT_ARRAY);
		int[] done = tag.getIntArray("Progress");
		for (int i = 0; i < list.size(); i++) {
			int[] a = list.getIntArray(i);
			if (a.length != 5 || (a[0] != a[2] && a[1] != a[3])) {
				continue; // not a valid axis-aligned segment: drop it rather than crash on a hand-edited world
			}
			roads.segments.add(RoadSegment.fromIntArray(a));
			roads.progress.add(i < done.length ? done[i] : 0);
		}
		roads.branches = tag.getInt("Branches");
		return roads;
	}
}
