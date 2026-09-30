package com.khanhvu.livingvillages.wall;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One ring of walls around a village (spec v3 §4): its outline and what happened to each column along it.
 * The column loop is rebuilt from the outline (deterministic), so only the outline and one status byte per column
 * are saved.
 */
public final class WallRing {
	public enum Type implements StringRepresentable {
		PALISADE("palisade"),
		CITY("city");

		private final String name;

		Type(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		public String langKey() {
			return "livingvillages.wall.type." + name;
		}

		@Nullable
		static Type byName(String name) {
			for (Type type : values()) {
				if (type.name.equals(name)) {
					return type;
				}
			}
			return null;
		}
	}

	/** Not built yet (or lost for a reason other than a player, to be built again). */
	public static final byte PENDING = 0;
	/** The column's wall stands. */
	public static final byte DONE = 1;
	/** Left open: water, a cliff, a player's building, a block a player broke (a weak point). */
	public static final byte WEAK = 2;
	/** Left open on purpose: a road crosses here. */
	public static final byte GATE = 3;

	private final int id;
	private final Type type;
	private final int[] polygon;
	private final byte[] status;
	private final long createdTick;
	/** The completion was announced once; repairs later do not announce it again. */
	private boolean completed;
	/** Column loop along the polygon; derived, not saved. */
	private final int[] columns;
	/** Column indices in building order; derived lazily from the gates. */
	@Nullable
	private int[] order;

	public WallRing(int id, Type type, int[] polygon, long createdTick) {
		this(id, type, polygon, null, createdTick);
	}

	private WallRing(int id, Type type, int[] polygon, @Nullable byte[] status, long createdTick) {
		this.id = id;
		this.type = type;
		this.polygon = polygon;
		this.columns = VillageBoundary.columns(polygon);
		// A saved status of another length (should not happen) is dropped: the wall is simply checked again.
		this.status = status != null && status.length == columns.length / 2 ? status : new byte[columns.length / 2];
		this.createdTick = createdTick;
	}

	public int getId() {
		return id;
	}

	public Type getType() {
		return type;
	}

	public int[] getPolygon() {
		return polygon;
	}

	public long getCreatedTick() {
		return createdTick;
	}

	public int size() {
		return status.length;
	}

	public int x(int column) {
		return columns[column * 2];
	}

	public int z(int column) {
		return columns[column * 2 + 1];
	}

	public byte status(int column) {
		return status[column];
	}

	public void setStatus(int column, byte value) {
		byte old = status[column];
		status[column] = value;
		if (value == GATE || old == GATE) {
			order = null; // the building order starts from the gates
		}
	}

	public boolean isCompleted() {
		return completed;
	}

	public void setCompleted(boolean completed) {
		this.completed = completed;
	}

	public boolean contains(double x, double z) {
		return VillageBoundary.contains(polygon, x, z);
	}

	public int count(byte value) {
		int count = 0;
		for (byte b : status) {
			if (b == value) {
				count++;
			}
		}
		return count;
	}

	/** Runs of consecutive columns with this status (the loop wraps around): gates, weak points. */
	public int runs(byte value) {
		int n = status.length;
		int runs = 0;
		for (int i = 0; i < n; i++) {
			if (status[i] == value && status[(i + n - 1) % n] != value) {
				runs++;
			}
		}
		return runs == 0 && n > 0 && status[0] == value ? 1 : runs;
	}

	/** Share of the loop that is settled (built, gate or weak point), 0–100. */
	public int progressPercent() {
		return status.length == 0 ? 100 : 100 * (status.length - count(PENDING)) / status.length;
	}

	/**
	 * Column indices in building order: from the gates outwards on both sides (spec v3 §5), so the wall grows from the
	 * gates; without gates, around the loop from the first column.
	 */
	public int[] order() {
		if (order != null) {
			return order;
		}
		int n = status.length;
		int[] distance = new int[n];
		Arrays.fill(distance, Integer.MAX_VALUE);
		List<Integer> queue = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (status[i] == GATE) {
				distance[i] = 0;
				queue.add(i);
			}
		}
		if (queue.isEmpty() && n > 0) {
			distance[0] = 0;
			queue.add(0);
		}
		// Breadth-first along the loop.
		for (int head = 0; head < queue.size(); head++) {
			int i = queue.get(head);
			for (int next : new int[] {(i + 1) % n, (i + n - 1) % n}) {
				if (distance[next] == Integer.MAX_VALUE) {
					distance[next] = distance[i] + 1;
					queue.add(next);
				}
			}
		}
		order = queue.stream().mapToInt(Integer::intValue).toArray();
		return order;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Id", id);
		tag.putString("Type", type.getSerializedName());
		tag.putIntArray("Polygon", polygon);
		tag.putByteArray("Status", status);
		tag.putLong("Created", createdTick);
		tag.putBoolean("Completed", completed);
		return tag;
	}

	@Nullable
	public static WallRing load(CompoundTag tag) {
		Type type = Type.byName(tag.getString("Type"));
		int[] polygon = tag.getIntArray("Polygon");
		if (type == null || polygon.length < 6 || polygon.length % 2 != 0) {
			return null;
		}
		WallRing ring = new WallRing(tag.getInt("Id"), type, polygon, tag.getByteArray("Status"), tag.getLong("Created"));
		ring.completed = tag.getBoolean("Completed");
		return ring;
	}
}
