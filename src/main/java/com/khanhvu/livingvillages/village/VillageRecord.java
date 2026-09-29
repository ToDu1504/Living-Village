package com.khanhvu.livingvillages.village;

import com.khanhvu.livingvillages.build.BuildProject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent state of one village, identified by its bell.
 */
public class VillageRecord {
	/** Sentinel for "never built a house", so no cooldown applies. */
	public static final long NEVER = Long.MIN_VALUE;

	private final UUID id;
	private BlockPos bellPos;
	/** Fixed at the first analysis so new houses keep the same style; null until then. */
	@Nullable
	private VillageType villageType;
	private boolean active = true;
	private int housesBuilt;
	private long lastBuildTick = NEVER;
	private int failedSiteAttempts;
	private final List<BoundingBox> plots = new ArrayList<>();
	@Nullable
	private BuildProject project;
	/** Saplings still to replant for trees felled on building sites, planted near {@code near}. */
	private final List<PendingSapling> pendingSaplings = new ArrayList<>();
	/** After a failed site search, no new search before this game tick. Not saved: a restart simply retries. */
	private long nextSiteAttemptTick;

	public VillageRecord(UUID id, BlockPos bellPos) {
		this.id = id;
		this.bellPos = bellPos.immutable();
	}

	public UUID getId() {
		return id;
	}

	public BlockPos getBellPos() {
		return bellPos;
	}

	public void setBellPos(BlockPos bellPos) {
		this.bellPos = bellPos.immutable();
	}

	@Nullable
	public VillageType getVillageType() {
		return villageType;
	}

	public void setVillageType(VillageType villageType) {
		this.villageType = villageType;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public int getHousesBuilt() {
		return housesBuilt;
	}

	public void setHousesBuilt(int housesBuilt) {
		this.housesBuilt = housesBuilt;
	}

	public long getLastBuildTick() {
		return lastBuildTick;
	}

	public void setLastBuildTick(long lastBuildTick) {
		this.lastBuildTick = lastBuildTick;
	}

	public int getFailedSiteAttempts() {
		return failedSiteAttempts;
	}

	public void setFailedSiteAttempts(int failedSiteAttempts) {
		this.failedSiteAttempts = failedSiteAttempts;
	}

	public List<BoundingBox> getPlots() {
		return plots;
	}

	@Nullable
	public BuildProject getProject() {
		return project;
	}

	public void setProject(@Nullable BuildProject project) {
		this.project = project;
	}

	public long getNextSiteAttemptTick() {
		return nextSiteAttemptTick;
	}

	public void setNextSiteAttemptTick(long nextSiteAttemptTick) {
		this.nextSiteAttemptTick = nextSiteAttemptTick;
	}

	public record PendingSapling(ResourceLocation sapling, BlockPos near) {
	}

	public List<PendingSapling> getPendingSaplings() {
		return pendingSaplings;
	}

	/** Bookkeeping when a house is finished: the plot is reserved and the cooldown starts. */
	public void recordHouseBuilt(BoundingBox plot, long gameTime) {
		plots.add(plot);
		housesBuilt++;
		lastBuildTick = gameTime;
		failedSiteAttempts = 0;
	}

	/** Squared horizontal distance from the bell, used for village membership and merging. */
	public double horizontalDistSqr(BlockPos pos) {
		double dx = pos.getX() - bellPos.getX();
		double dz = pos.getZ() - bellPos.getZ();
		return dx * dx + dz * dz;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", id);
		tag.put("Bell", NbtUtils.writeBlockPos(bellPos));
		if (villageType != null) {
			tag.putString("Type", villageType.getSerializedName());
		}
		tag.putBoolean("Active", active);
		tag.putInt("HousesBuilt", housesBuilt);
		tag.putLong("LastBuildTick", lastBuildTick);
		tag.putInt("FailedSiteAttempts", failedSiteAttempts);
		ListTag plotList = new ListTag();
		for (BoundingBox box : plots) {
			plotList.add(new IntArrayTag(new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}));
		}
		tag.put("Plots", plotList);
		if (project != null) {
			tag.put("Project", project.save());
		}
		ListTag saplings = new ListTag();
		for (PendingSapling pending : pendingSaplings) {
			CompoundTag entry = new CompoundTag();
			entry.putString("Sapling", pending.sapling().toString());
			entry.putLong("Near", pending.near().asLong());
			saplings.add(entry);
		}
		tag.put("PendingSaplings", saplings);
		return tag;
	}

	@Nullable
	public static VillageRecord load(CompoundTag tag) {
		if (!tag.hasUUID("Id")) {
			return null;
		}
		BlockPos bell = NbtUtils.readBlockPos(tag, "Bell").orElse(null);
		if (bell == null) {
			return null;
		}
		VillageRecord record = new VillageRecord(tag.getUUID("Id"), bell);
		if (tag.contains("Type", Tag.TAG_STRING)) {
			record.villageType = VillageType.byName(tag.getString("Type"));
		}
		record.active = !tag.contains("Active") || tag.getBoolean("Active");
		record.housesBuilt = tag.getInt("HousesBuilt");
		record.lastBuildTick = tag.contains("LastBuildTick") ? tag.getLong("LastBuildTick") : NEVER;
		record.failedSiteAttempts = tag.getInt("FailedSiteAttempts");
		ListTag plotList = tag.getList("Plots", Tag.TAG_INT_ARRAY);
		for (int i = 0; i < plotList.size(); i++) {
			int[] a = plotList.getIntArray(i);
			if (a.length == 6) {
				record.plots.add(new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]));
			}
		}
		if (tag.contains("Project", Tag.TAG_COMPOUND)) {
			record.project = BuildProject.load(tag.getCompound("Project"));
		}
		ListTag saplings = tag.getList("PendingSaplings", Tag.TAG_COMPOUND);
		for (int i = 0; i < saplings.size(); i++) {
			CompoundTag entry = saplings.getCompound(i);
			ResourceLocation sapling = ResourceLocation.tryParse(entry.getString("Sapling"));
			if (sapling != null) {
				record.pendingSaplings.add(new PendingSapling(sapling, BlockPos.of(entry.getLong("Near"))));
			}
		}
		return record;
	}
}
