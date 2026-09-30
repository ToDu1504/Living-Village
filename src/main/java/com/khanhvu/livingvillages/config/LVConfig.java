package com.khanhvu.livingvillages.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.khanhvu.livingvillages.LivingVillages;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mod configuration stored in config/livingvillages.json.
 * Field names are the JSON keys; invalid values fall back to defaults with a warning.
 */
public class LVConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("livingvillages.json");

	/** Languages with a bundled lang file. */
	public static final Set<String> SUPPORTED_LANGUAGES = Set.of("vi_vn", "en_us");

	private static LVConfig instance = new LVConfig();

	/** Language of every text players see (bundled lang file), since vanilla clients have no lang file for the mod. */
	public String language = "vi_vn";
	public boolean enabled = true;
	public int scanIntervalTicks = 100;
	public int manageIntervalTicks = 40;
	public int scanRadius = 64;
	public int villageRadius = 48;
	public int villageMergeRadius = 48;
	public int activeRange = 128;
	public int freeBedThreshold = 0;
	public int maxHousesPerVillage = 10;
	public int cooldownTicks = 24000;
	public int minBuildDistance = 12;
	public int maxBuildDistance = 64;
	public int siteAttempts = 48;
	public int margin = 2;
	public int maxHeightDifference = 3;
	public int templateYOffset = 0;
	public int maxSiteFailures = 5;
	public double blocksPerSecond = 2.0;
	public double speedMultiplier = 1.0;
	public int maxBlocksPerTickGlobal = 4;
	public int builderReach = 8;
	public int builderStuckTicks = 1200;
	public int workStartTime = 1000;
	public int workEndTime = 11000;
	public double maxSkippedRatio = 0.1;
	public boolean showBuilderHeldItem = true;
	public boolean debugLogging = false;

	// v2-GĐ 1: natural trees in building sites
	public boolean allowTreeClearing = true;
	public int maxTreeLogs = 40;
	public int maxTreesPerSite = 4;
	public boolean replantSaplings = true;
	public int workTimeoutTicks = 600;

	// v2-GĐ 2: needs, mood, leader
	public boolean needsEnabled = true;
	public double farmersPerVillager = 0.25;
	/** Iron golems (and guards) wanted per adult villager for full safety. */
	public double defendersPerVillager = 0.1;
	/** Safety points lost per recent monster attack on a villager. */
	public int attackPenalty = 10;
	/** Recent attacks fade by half over this many ticks. */
	public int attackHalfLifeTicks = 24000;
	/** Temporary mood effects (new building, death...) fade to 0 over this many ticks. */
	public int moodEffectTicks = 72000;
	public int needThreshold = 40;
	public int leaderAbsentTicks = 12000;

	// v2-GĐ 3: build by needs, village levels
	public boolean levelsEnabled = true;
	/** Requirements to reach level 1 (Village), 2 (Town) and 3 (City); level 0 (Hamlet) is the start. */
	public List<LevelRequirement> levelRequirements = List.of(
			new LevelRequirement(8, 3, List.of()),
			new LevelRequirement(20, 6, List.of("librarian")),
			new LevelRequirement(35, 10, List.of("librarian", "cleric")));
	public List<Integer> levelMaxBuildings = List.of(4, 10, 20, 32);
	public List<Integer> buildRadiusByLevel = List.of(48, 64, 80, 96);
	public boolean levelCanDecrease = false;

	// v2-GĐ 4: profession work and animals
	public boolean workEnabled = true;
	public int workIntervalTicks = 600;
	public double workChance = 0.5;
	/** Per profession (id without namespace): whether it does its v2 job. */
	public Map<String, Boolean> professionWork = defaultProfessionWork();
	/** Most animals of one kind the village breeds, by level. */
	public List<Integer> maxAnimalsPerType = List.of(6, 8, 12, 16);
	public int maxFishInBarrel = 16;
	public double toolsmithBuildSpeedBonus = 0.1;
	public double toolsmithBuildSpeedMax = 0.3;
	public int cartographerRadiusBonus = 16;
	/** Safety points per armorer or weaponsmith, and the most they add together. */
	public int smithSafetyBonus = 5;
	public int smithSafetyMax = 20;
	public boolean clericCureZombies = true;
	public int clericCuresPerDay = 1;
	public int clericCureRange = 4;

	// v2-GĐ 5: identity
	public boolean nameVillagers = true;
	public boolean nameGuards = true;
	public boolean showEntryTitle = true;
	public int greetingCooldownTicks = 6000;

	// v2-GĐ 6: voice
	public boolean voiceEnabled = true;
	public int voiceRange = 24;
	public int voiceIntervalTicks = 200;
	public double voiceChance = 0.35;
	public int maxBubblesPerVillage = 2;
	public int bubbleDurationTicks = 80;

	private static Map<String, Boolean> defaultProfessionWork() {
		Map<String, Boolean> map = new LinkedHashMap<>();
		for (String id : List.of("shepherd", "butcher", "leatherworker", "fletcher", "fisherman", "cleric", "armorer",
				"weaponsmith", "toolsmith", "cartographer", "librarian", "mason")) {
			map.put(id, true);
		}
		return map;
	}

	public static class LevelRequirement {
		public int villagers;
		public int professions;
		/** Professions (ids without namespace, e.g. "librarian") that must be present. */
		public List<String> services = List.of();

		public LevelRequirement() {
		}

		public LevelRequirement(int villagers, int professions, List<String> services) {
			this.villagers = villagers;
			this.professions = professions;
			this.services = services;
		}
	}

	public static LVConfig get() {
		return instance;
	}

	/**
	 * Loads the config file, creating it with defaults if missing. Always leaves a usable config.
	 * Returns false when the file exists but could not be read (defaults are used, the file is left untouched).
	 */
	public static boolean load() {
		LVConfig loaded = null;
		boolean readFailed = false;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, LVConfig.class);
			} catch (IOException | JsonParseException e) {
				readFailed = true;
				LivingVillages.LOGGER.warn("[LivingVillages] Could not read {}, using defaults: {}", PATH, e.getMessage());
			}
		}
		if (loaded == null) {
			loaded = new LVConfig();
		}
		loaded.validate();
		instance = loaded;
		// Keep a broken file untouched so the user can fix it; otherwise write back (adds missing keys).
		if (!readFailed) {
			save();
		}
		return !readFailed;
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			LivingVillages.LOGGER.warn("[LivingVillages] Could not write {}: {}", PATH, e.getMessage());
		}
	}

	private void validate() {
		LVConfig d = new LVConfig();
		scanIntervalTicks = checkInt("scanIntervalTicks", scanIntervalTicks, 1, 72000, d.scanIntervalTicks);
		manageIntervalTicks = checkInt("manageIntervalTicks", manageIntervalTicks, 1, 72000, d.manageIntervalTicks);
		scanRadius = checkInt("scanRadius", scanRadius, 8, 256, d.scanRadius);
		villageRadius = checkInt("villageRadius", villageRadius, 8, 256, d.villageRadius);
		villageMergeRadius = checkInt("villageMergeRadius", villageMergeRadius, 8, 512, d.villageMergeRadius);
		activeRange = checkInt("activeRange", activeRange, 16, 1024, d.activeRange);
		freeBedThreshold = checkInt("freeBedThreshold", freeBedThreshold, 0, 64, d.freeBedThreshold);
		maxHousesPerVillage = checkInt("maxHousesPerVillage", maxHousesPerVillage, 0, 1000, d.maxHousesPerVillage);
		cooldownTicks = checkInt("cooldownTicks", cooldownTicks, 0, Integer.MAX_VALUE, d.cooldownTicks);
		minBuildDistance = checkInt("minBuildDistance", minBuildDistance, 0, 256, d.minBuildDistance);
		maxBuildDistance = checkInt("maxBuildDistance", maxBuildDistance, 1, 256, d.maxBuildDistance);
		if (maxBuildDistance < minBuildDistance) {
			warn("maxBuildDistance", maxBuildDistance);
			minBuildDistance = d.minBuildDistance;
			maxBuildDistance = d.maxBuildDistance;
		}
		siteAttempts = checkInt("siteAttempts", siteAttempts, 1, 1024, d.siteAttempts);
		margin = checkInt("margin", margin, 0, 16, d.margin);
		maxHeightDifference = checkInt("maxHeightDifference", maxHeightDifference, 0, 32, d.maxHeightDifference);
		templateYOffset = checkInt("templateYOffset", templateYOffset, -16, 16, d.templateYOffset);
		maxSiteFailures = checkInt("maxSiteFailures", maxSiteFailures, 1, 1000, d.maxSiteFailures);
		blocksPerSecond = checkDouble("blocksPerSecond", blocksPerSecond, 0.01, 1000.0, d.blocksPerSecond);
		speedMultiplier = checkDouble("speedMultiplier", speedMultiplier, 0.1, 10.0, d.speedMultiplier);
		maxBlocksPerTickGlobal = checkInt("maxBlocksPerTickGlobal", maxBlocksPerTickGlobal, 1, 1000, d.maxBlocksPerTickGlobal);
		builderReach = checkInt("builderReach", builderReach, 1, 64, d.builderReach);
		builderStuckTicks = checkInt("builderStuckTicks", builderStuckTicks, 20, 72000, d.builderStuckTicks);
		workStartTime = checkInt("workStartTime", workStartTime, 0, 23999, d.workStartTime);
		workEndTime = checkInt("workEndTime", workEndTime, 0, 23999, d.workEndTime);
		maxSkippedRatio = checkDouble("maxSkippedRatio", maxSkippedRatio, 0.0, 1.0, d.maxSkippedRatio);
		if (language == null || !SUPPORTED_LANGUAGES.contains(language)) {
			warn("language", language);
			language = d.language;
		}
		maxTreeLogs = checkInt("maxTreeLogs", maxTreeLogs, 1, 256, d.maxTreeLogs);
		maxTreesPerSite = checkInt("maxTreesPerSite", maxTreesPerSite, 0, 32, d.maxTreesPerSite);
		workTimeoutTicks = checkInt("workTimeoutTicks", workTimeoutTicks, 20, 72000, d.workTimeoutTicks);
		farmersPerVillager = checkDouble("farmersPerVillager", farmersPerVillager, 0.01, 1.0, d.farmersPerVillager);
		defendersPerVillager = checkDouble("defendersPerVillager", defendersPerVillager, 0.01, 1.0, d.defendersPerVillager);
		attackPenalty = checkInt("attackPenalty", attackPenalty, 0, 100, d.attackPenalty);
		attackHalfLifeTicks = checkInt("attackHalfLifeTicks", attackHalfLifeTicks, 20, 720000, d.attackHalfLifeTicks);
		moodEffectTicks = checkInt("moodEffectTicks", moodEffectTicks, 20, 720000, d.moodEffectTicks);
		needThreshold = checkInt("needThreshold", needThreshold, 0, 100, d.needThreshold);
		leaderAbsentTicks = checkInt("leaderAbsentTicks", leaderAbsentTicks, 20, 720000, d.leaderAbsentTicks);
		if (levelRequirements == null || levelRequirements.size() != 3 || levelRequirements.stream().anyMatch(q -> q == null || q.villagers < 0 || q.professions < 0)) {
			warn("levelRequirements", levelRequirements);
			levelRequirements = d.levelRequirements;
		}
		for (LevelRequirement requirement : levelRequirements) {
			if (requirement.services == null) {
				requirement.services = List.of();
			}
		}
		if (levelMaxBuildings == null || levelMaxBuildings.size() != 4 || levelMaxBuildings.stream().anyMatch(n -> n == null || n < 0 || n > 1000)) {
			warn("levelMaxBuildings", levelMaxBuildings);
			levelMaxBuildings = d.levelMaxBuildings;
		}
		workIntervalTicks = checkInt("workIntervalTicks", workIntervalTicks, 20, 72000, d.workIntervalTicks);
		workChance = checkDouble("workChance", workChance, 0.0, 1.0, d.workChance);
		if (professionWork == null) {
			warn("professionWork", null);
			professionWork = d.professionWork;
		}
		if (maxAnimalsPerType == null || maxAnimalsPerType.size() != 4 || maxAnimalsPerType.stream().anyMatch(n -> n == null || n < 0 || n > 256)) {
			warn("maxAnimalsPerType", maxAnimalsPerType);
			maxAnimalsPerType = d.maxAnimalsPerType;
		}
		maxFishInBarrel = checkInt("maxFishInBarrel", maxFishInBarrel, 0, 27 * 64, d.maxFishInBarrel);
		toolsmithBuildSpeedBonus = checkDouble("toolsmithBuildSpeedBonus", toolsmithBuildSpeedBonus, 0.0, 1.0, d.toolsmithBuildSpeedBonus);
		toolsmithBuildSpeedMax = checkDouble("toolsmithBuildSpeedMax", toolsmithBuildSpeedMax, 0.0, 5.0, d.toolsmithBuildSpeedMax);
		cartographerRadiusBonus = checkInt("cartographerRadiusBonus", cartographerRadiusBonus, 0, 128, d.cartographerRadiusBonus);
		smithSafetyBonus = checkInt("smithSafetyBonus", smithSafetyBonus, 0, 100, d.smithSafetyBonus);
		smithSafetyMax = checkInt("smithSafetyMax", smithSafetyMax, 0, 100, d.smithSafetyMax);
		clericCuresPerDay = checkInt("clericCuresPerDay", clericCuresPerDay, 0, 100, d.clericCuresPerDay);
		clericCureRange = checkInt("clericCureRange", clericCureRange, 1, 16, d.clericCureRange);
		greetingCooldownTicks = checkInt("greetingCooldownTicks", greetingCooldownTicks, 0, 720000, d.greetingCooldownTicks);
		voiceRange = checkInt("voiceRange", voiceRange, 4, 128, d.voiceRange);
		voiceIntervalTicks = checkInt("voiceIntervalTicks", voiceIntervalTicks, 20, 72000, d.voiceIntervalTicks);
		voiceChance = checkDouble("voiceChance", voiceChance, 0.0, 1.0, d.voiceChance);
		maxBubblesPerVillage = checkInt("maxBubblesPerVillage", maxBubblesPerVillage, 0, 16, d.maxBubblesPerVillage);
		bubbleDurationTicks = checkInt("bubbleDurationTicks", bubbleDurationTicks, 20, 1200, d.bubbleDurationTicks);
		if (buildRadiusByLevel == null || buildRadiusByLevel.size() != 4 || buildRadiusByLevel.stream().anyMatch(n -> n == null || n < minBuildDistance || n > 256)) {
			warn("buildRadiusByLevel", buildRadiusByLevel);
			buildRadiusByLevel = d.buildRadiusByLevel;
		}
	}

	private static int checkInt(String name, int value, int min, int max, int fallback) {
		if (value < min || value > max) {
			warn(name, value);
			return fallback;
		}
		return value;
	}

	private static double checkDouble(String name, double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || value < min || value > max) {
			warn(name, value);
			return fallback;
		}
		return value;
	}

	private static void warn(String name, Object value) {
		LivingVillages.LOGGER.warn("[LivingVillages] Invalid config value {} = {}, using default", name, value);
	}
}
