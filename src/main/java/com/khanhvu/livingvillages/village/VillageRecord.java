package com.khanhvu.livingvillages.village;

import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.society.VillageNeeds;
import com.khanhvu.livingvillages.work.ProfessionWork;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	// v2-GĐ 2: society state (saved)
	@Nullable
	private UUID leaderUuid;
	private long leaderLastSeenTick;
	/** Names of villagers this mod gave a title, without the title, to restore when the title goes. */
	private final Map<UUID, String> titledBaseNames = new HashMap<>();
	/** Villagers seen in the village and the game tick they were first seen (tie-break when choosing a leader). */
	private final Map<UUID, Long> knownVillagers = new LinkedHashMap<>();
	/** Recent monster attacks on villagers; decays over time, see {@code AttackTracker}. */
	private double attackScore;
	private long attackScoreTick;
	private final List<MoodEffect> moodEffects = new ArrayList<>();
	/** Village level 0 (hamlet) to 3 (city); -1 until first computed. Only rises unless levelCanDecrease. */
	private int level = -1;

	// v2-GĐ 5: identity (saved)
	@Nullable
	private String name;
	/** Households: anchor position (plot centre or first bed seen) → surname. */
	private final Map<Long, String> households = new HashMap<>();
	/** Names this mod gave to villagers; a villager renamed since is left alone. */
	private final Map<UUID, String> givenNames = new HashMap<>();
	/** Adults counted at the last update (for the entry greeting); not saved. */
	private int lastAdultCount;

	// Recent events for villagers to talk about (not saved): name and game tick.
	@Nullable
	private String recentBirthName;
	private long recentBirthTick = Long.MIN_VALUE;
	@Nullable
	private String recentDeathName;
	private long recentDeathTick = Long.MIN_VALUE;
	/** A festival is going on (v2-GĐ 8); not saved. */
	private boolean festivalActive;

	// v2-GĐ 2: last computed values (not saved, recomputed every manage interval)
	@Nullable
	private VillageNeeds needs;
	private int mood = -1;
	/** Set when the leader entity was removed (death, conversion); a new leader is chosen at the next update. */
	private boolean leaderGone;
	/** Village-wide effects of professions (v2-GĐ 4), from the last update; none before it. */
	private ProfessionWork.Bonuses bonuses = new ProfessionWork.Bonuses(0, 0, 0);

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

	/** A temporary mood change of {@code amount} that fades linearly to 0 from {@code startTick}. */
	public record MoodEffect(int amount, long startTick) {
	}

	@Nullable
	public UUID getLeaderUuid() {
		return leaderUuid;
	}

	public void setLeaderUuid(@Nullable UUID leaderUuid) {
		this.leaderUuid = leaderUuid;
	}

	public long getLeaderLastSeenTick() {
		return leaderLastSeenTick;
	}

	public void setLeaderLastSeenTick(long leaderLastSeenTick) {
		this.leaderLastSeenTick = leaderLastSeenTick;
	}

	public Map<UUID, String> getTitledBaseNames() {
		return titledBaseNames;
	}

	public Map<UUID, Long> getKnownVillagers() {
		return knownVillagers;
	}

	public double getAttackScore() {
		return attackScore;
	}

	public long getAttackScoreTick() {
		return attackScoreTick;
	}

	public void setAttackScore(double attackScore, long tick) {
		this.attackScore = attackScore;
		this.attackScoreTick = tick;
	}

	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public List<MoodEffect> getMoodEffects() {
		return moodEffects;
	}

	@Nullable
	public VillageNeeds getNeeds() {
		return needs;
	}

	public void setNeeds(@Nullable VillageNeeds needs) {
		this.needs = needs;
	}

	/** Last computed mood 0–100, or -1 before the first update. */
	public int getMood() {
		return mood;
	}

	public void setMood(int mood) {
		this.mood = mood;
	}

	@Nullable
	public String getName() {
		return name;
	}

	public void setName(@Nullable String name) {
		this.name = name;
	}

	public Map<Long, String> getHouseholds() {
		return households;
	}

	public Map<UUID, String> getGivenNames() {
		return givenNames;
	}

	@Nullable
	public String getRecentBirthName() {
		return recentBirthName;
	}

	public long getRecentBirthTick() {
		return recentBirthTick;
	}

	public void setRecentBirth(String name, long tick) {
		this.recentBirthName = name;
		this.recentBirthTick = tick;
	}

	@Nullable
	public String getRecentDeathName() {
		return recentDeathName;
	}

	public long getRecentDeathTick() {
		return recentDeathTick;
	}

	public void setRecentDeath(String name, long tick) {
		this.recentDeathName = name;
		this.recentDeathTick = tick;
	}

	public boolean isFestivalActive() {
		return festivalActive;
	}

	public void setFestivalActive(boolean festivalActive) {
		this.festivalActive = festivalActive;
	}

	public int getLastAdultCount() {
		return lastAdultCount;
	}

	public void setLastAdultCount(int lastAdultCount) {
		this.lastAdultCount = lastAdultCount;
	}

	public ProfessionWork.Bonuses getBonuses() {
		return bonuses;
	}

	public void setBonuses(ProfessionWork.Bonuses bonuses) {
		this.bonuses = bonuses;
	}

	public boolean isLeaderGone() {
		return leaderGone;
	}

	public void setLeaderGone(boolean leaderGone) {
		this.leaderGone = leaderGone;
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
		if (leaderUuid != null) {
			tag.putUUID("Leader", leaderUuid);
		}
		tag.putLong("LeaderLastSeen", leaderLastSeenTick);
		ListTag titled = new ListTag();
		for (Map.Entry<UUID, String> entry : titledBaseNames.entrySet()) {
			CompoundTag item = new CompoundTag();
			item.putUUID("Id", entry.getKey());
			item.putString("Name", entry.getValue());
			titled.add(item);
		}
		tag.put("TitledNames", titled);
		ListTag known = new ListTag();
		for (Map.Entry<UUID, Long> entry : knownVillagers.entrySet()) {
			CompoundTag item = new CompoundTag();
			item.putUUID("Id", entry.getKey());
			item.putLong("Since", entry.getValue());
			known.add(item);
		}
		tag.put("KnownVillagers", known);
		tag.putDouble("AttackScore", attackScore);
		tag.putLong("AttackScoreTick", attackScoreTick);
		ListTag effects = new ListTag();
		for (MoodEffect effect : moodEffects) {
			CompoundTag item = new CompoundTag();
			item.putInt("Amount", effect.amount());
			item.putLong("Start", effect.startTick());
			effects.add(item);
		}
		tag.put("MoodEffects", effects);
		tag.putInt("Level", level);
		if (name != null) {
			tag.putString("Name", name);
		}
		ListTag householdList = new ListTag();
		for (Map.Entry<Long, String> entry : households.entrySet()) {
			CompoundTag item = new CompoundTag();
			item.putLong("Anchor", entry.getKey());
			item.putString("Surname", entry.getValue());
			householdList.add(item);
		}
		tag.put("Households", householdList);
		ListTag named = new ListTag();
		for (Map.Entry<UUID, String> entry : givenNames.entrySet()) {
			CompoundTag item = new CompoundTag();
			item.putUUID("Id", entry.getKey());
			item.putString("Name", entry.getValue());
			named.add(item);
		}
		tag.put("GivenNames", named);
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
		// Fields added in v2: absent in 0.1 worlds, so every read has a default.
		if (tag.hasUUID("Leader")) {
			record.leaderUuid = tag.getUUID("Leader");
		}
		record.leaderLastSeenTick = tag.getLong("LeaderLastSeen");
		ListTag titled = tag.getList("TitledNames", Tag.TAG_COMPOUND);
		for (int i = 0; i < titled.size(); i++) {
			CompoundTag item = titled.getCompound(i);
			if (item.hasUUID("Id")) {
				record.titledBaseNames.put(item.getUUID("Id"), item.getString("Name"));
			}
		}
		ListTag known = tag.getList("KnownVillagers", Tag.TAG_COMPOUND);
		for (int i = 0; i < known.size(); i++) {
			CompoundTag item = known.getCompound(i);
			if (item.hasUUID("Id")) {
				record.knownVillagers.put(item.getUUID("Id"), item.getLong("Since"));
			}
		}
		record.attackScore = tag.getDouble("AttackScore");
		record.attackScoreTick = tag.getLong("AttackScoreTick");
		ListTag effects = tag.getList("MoodEffects", Tag.TAG_COMPOUND);
		for (int i = 0; i < effects.size(); i++) {
			CompoundTag item = effects.getCompound(i);
			record.moodEffects.add(new MoodEffect(item.getInt("Amount"), item.getLong("Start")));
		}
		record.level = tag.contains("Level") ? tag.getInt("Level") : -1;
		if (tag.contains("Name", Tag.TAG_STRING)) {
			record.name = tag.getString("Name");
		}
		ListTag householdList = tag.getList("Households", Tag.TAG_COMPOUND);
		for (int i = 0; i < householdList.size(); i++) {
			CompoundTag item = householdList.getCompound(i);
			record.households.put(item.getLong("Anchor"), item.getString("Surname"));
		}
		ListTag named = tag.getList("GivenNames", Tag.TAG_COMPOUND);
		for (int i = 0; i < named.size(); i++) {
			CompoundTag item = named.getCompound(i);
			if (item.hasUUID("Id")) {
				record.givenNames.put(item.getUUID("Id"), item.getString("Name"));
			}
		}
		return record;
	}
}
