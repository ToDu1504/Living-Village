package com.khanhvu.livingvillages.wall;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One ring of walls around a village (spec v3 §4): its outline, what happened to each column along it, and its
 * towers. The column loop is rebuilt from the outline (deterministic), so only the outline, one status byte per
 * column and the towers are saved.
 * <p>
 * Work is done in units: units {@code 0..size()-1} are the columns, units {@code size()..} the towers.
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
	/** A road crosses here: left open; a city wall still has to put the arch over it. */
	public static final byte GATE = 3;
	/** A gate whose arch stands (city walls). */
	public static final byte GATE_DONE = 4;
	/** Built, but the ground right outside is about as high as the wall top (a weak point all the same). */
	public static final byte EXPOSED = 5;
	/** Part of a tower: the tower builds it. */
	public static final byte TOWER = 6;

	/** A 5×5 watch tower whose inner face stands on the wall line (spec v3-GĐ 2). */
	public static final class Tower {
		/** The wall column in the middle of the tower's inner face (where the door is). */
		public final int x;
		public final int z;
		/** Ground level of the door column; the tower floor. */
		public final int baseY;
		/** Horizontal direction from the wall line out of the village. */
		public final Direction out;
		byte status;

		public Tower(int x, int z, int baseY, Direction out) {
			this.x = x;
			this.z = z;
			this.baseY = baseY;
			this.out = out;
		}

		public byte status() {
			return status;
		}

		/** Centre of the 5×5 square. */
		public int centerX() {
			return x + out.getStepX() * 2;
		}

		public int centerZ() {
			return z + out.getStepZ() * 2;
		}

		public boolean covers(int px, int pz) {
			return Math.abs(px - centerX()) <= 2 && Math.abs(pz - centerZ()) <= 2;
		}

		CompoundTag save() {
			CompoundTag tag = new CompoundTag();
			tag.putInt("X", x);
			tag.putInt("Z", z);
			tag.putInt("Y", baseY);
			tag.putString("Out", out.getSerializedName());
			tag.putByte("Status", status);
			return tag;
		}

		@Nullable
		static Tower load(CompoundTag tag) {
			Direction out = Direction.byName(tag.getString("Out"));
			if (out == null || out.getAxis().isVertical()) {
				return null;
			}
			Tower tower = new Tower(tag.getInt("X"), tag.getInt("Z"), tag.getInt("Y"), out);
			tower.status = tag.getByte("Status");
			return tower;
		}
	}

	private final int id;
	private final Type type;
	private final int[] polygon;
	private final byte[] status;
	private final List<Tower> towers = new ArrayList<>();
	private final long createdTick;
	/** Wall height above the ground (city walls). */
	private int height;
	/** Battlements on top (city walls at level City). */
	private boolean battlements;
	/** The completion was announced once; repairs later do not announce it again. */
	private boolean completed;
	private long completedTick = Long.MIN_VALUE;
	/** No room left inside for another house or workshop (spec v3-GĐ 3). */
	private boolean full;
	/** "Full" was written in the chronicle once; a later check that finds it full again stays quiet. */
	private boolean fullNoted;
	/** Column loop along the polygon; derived, not saved. */
	private final int[] columns;
	/** Units in building order; derived lazily. */
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

	public int getHeight() {
		return height;
	}

	public boolean hasBattlements() {
		return battlements;
	}

	public void setHeight(int height, boolean battlements) {
		this.height = height;
		this.battlements = battlements;
	}

	/** Number of columns. */
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
		boolean wasGate = isGate(status[column]);
		status[column] = value;
		if (wasGate != isGate(value)) {
			order = null; // the building order starts from the gates
		}
	}

	public static boolean isGate(byte value) {
		return value == GATE || value == GATE_DONE;
	}

	public static boolean isWeak(byte value) {
		return value == WEAK || value == EXPOSED;
	}

	public List<Tower> getTowers() {
		return towers;
	}

	public void addTower(Tower tower) {
		towers.add(tower);
		order = null;
	}

	public boolean isCompleted() {
		return completed;
	}

	public long getCompletedTick() {
		return completedTick;
	}

	public void setCompleted(long tick) {
		this.completed = true;
		this.completedTick = tick;
	}

	public boolean isFull() {
		return full;
	}

	public void setFull(boolean full) {
		this.full = full;
	}

	/** Marks the ring full and tells whether this is the first time. */
	public boolean noteFull() {
		full = true;
		boolean first = !fullNoted;
		fullNoted = true;
		return first;
	}

	public boolean contains(double x, double z) {
		return VillageBoundary.contains(polygon, x, z);
	}

	// ---------------------------------------------------------------- units

	public int units() {
		return status.length + towers.size();
	}

	public boolean isTowerUnit(int unit) {
		return unit >= status.length;
	}

	public Tower tower(int unit) {
		return towers.get(unit - status.length);
	}

	public int towerUnit(Tower tower) {
		return status.length + towers.indexOf(tower);
	}

	/** Whether the unit still needs work: a column not built, a city gate without its arch, a tower not finished. */
	public boolean isOpen(int unit) {
		if (isTowerUnit(unit)) {
			return tower(unit).status == PENDING;
		}
		byte value = status[unit];
		return value == PENDING || (value == GATE && type == Type.CITY);
	}

	/** Marks a unit to be worked on again (a lost block). */
	public void reopen(int unit) {
		if (isTowerUnit(unit)) {
			tower(unit).status = PENDING;
		} else if (status[unit] == GATE_DONE) {
			status[unit] = GATE;
		} else if (status[unit] != GATE && status[unit] != TOWER) {
			status[unit] = PENDING;
		}
	}

	public void setTowerStatus(int unit, byte value) {
		tower(unit).status = value;
	}

	/** Where a worker stands for the unit: the column, or the tower door. */
	public int unitX(int unit) {
		return isTowerUnit(unit) ? tower(unit).x : x(unit);
	}

	public int unitZ(int unit) {
		return isTowerUnit(unit) ? tower(unit).z : z(unit);
	}

	public int openUnits() {
		int count = 0;
		for (int unit = 0; unit < units(); unit++) {
			if (isOpen(unit)) {
				count++;
			}
		}
		return count;
	}

	// ---------------------------------------------------------------- counts

	public int count(byte value) {
		int count = 0;
		for (byte b : status) {
			if (b == value) {
				count++;
			}
		}
		return count;
	}

	/** Gates: runs of gate columns (the loop wraps around). */
	public int gateCount() {
		return runs(true);
	}

	/** Weak points: runs of open or exposed columns. */
	public int weakCount() {
		return runs(false);
	}

	private int runs(boolean gates) {
		int n = status.length;
		int runs = 0;
		for (int i = 0; i < n; i++) {
			boolean here = gates ? isGate(status[i]) : isWeak(status[i]);
			boolean before = gates ? isGate(status[(i + n - 1) % n]) : isWeak(status[(i + n - 1) % n]);
			if (here && !before) {
				runs++;
			}
		}
		boolean all = n > 0 && (gates ? isGate(status[0]) : isWeak(status[0]));
		return runs == 0 && all ? 1 : runs;
	}

	/** Share of the work that is settled, 0–100. */
	public int progressPercent() {
		int units = units();
		return units == 0 ? 100 : 100 * (units - openUnits()) / units;
	}

	/**
	 * Units in building order (spec v3 §5): towers first, then columns from the gates outwards on both sides, so the
	 * wall grows from the gates; without gates, around the loop from the first column.
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
			if (isGate(status[i])) {
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
		int[] result = new int[towers.size() + queue.size()];
		for (int t = 0; t < towers.size(); t++) {
			result[t] = n + t;
		}
		for (int i = 0; i < queue.size(); i++) {
			result[towers.size() + i] = queue.get(i);
		}
		order = result;
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
		tag.putLong("CompletedTick", completedTick);
		tag.putInt("Height", height);
		tag.putBoolean("Battlements", battlements);
		tag.putBoolean("Full", full);
		tag.putBoolean("FullNoted", fullNoted);
		ListTag towerList = new ListTag();
		for (Tower tower : towers) {
			towerList.add(tower.save());
		}
		tag.put("Towers", towerList);
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
		ring.completedTick = tag.contains("CompletedTick") ? tag.getLong("CompletedTick") : Long.MIN_VALUE;
		ring.height = tag.getInt("Height");
		ring.battlements = tag.getBoolean("Battlements");
		ring.full = tag.getBoolean("Full");
		ring.fullNoted = tag.getBoolean("FullNoted");
		ListTag towerList = tag.getList("Towers", Tag.TAG_COMPOUND);
		for (int i = 0; i < towerList.size(); i++) {
			Tower tower = Tower.load(towerList.getCompound(i));
			if (tower != null) {
				ring.towers.add(tower);
			}
		}
		return ring;
	}
}
