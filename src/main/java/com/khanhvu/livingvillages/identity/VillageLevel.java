package com.khanhvu.livingvillages.identity;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.wall.CitySites;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Village levels (spec v2-GĐ 3.3): 0 Hamlet, 1 Village, 2 Town, 3 City. A level sets how many buildings the mod
 * may build and how far from the bell. Computed as soon as the village is known, then only rises (unless
 * {@code levelCanDecrease}).
 */
public final class VillageLevel {
	public static final int MAX = 3;
	private static final String[] NAMES = {"hamlet", "village", "town", "city"};

	private VillageLevel() {
	}

	/** Levels (and their limits) apply only when both needs and levels are enabled; otherwise 0.1 rules hold. */
	public static boolean enabled() {
		LVConfig config = LVConfig.get();
		return config.needsEnabled && config.levelsEnabled;
	}

	public static String langKey(int level) {
		return "livingvillages.level." + name(level);
	}

	/** Raw English name (hamlet/village/town/city) for logs, independent of the player-facing language. */
	public static String name(int level) {
		return NAMES[Math.max(0, Math.min(MAX, level))];
	}

	/**
	 * Buildings the mod may build for the village in total. Behind city walls there is no count: the room inside
	 * the walls is the limit (spec v3-GĐ 3).
	 */
	public static int buildLimit(VillageRecord village) {
		LVConfig config = LVConfig.get();
		if (enabled() && CitySites.active(village)) {
			return Integer.MAX_VALUE;
		}
		return enabled() ? config.levelMaxBuildings.get(Math.max(0, village.getLevel())) : config.maxHousesPerVillage;
	}

	/** Farthest distance from the bell for new buildings, including the cartographer's bonus (v2-GĐ 4). */
	public static int buildRadius(VillageRecord village) {
		LVConfig config = LVConfig.get();
		int base = enabled() ? config.buildRadiusByLevel.get(Math.max(0, village.getLevel())) : config.maxBuildDistance;
		return base + village.getBonuses().radius();
	}

	/**
	 * People counted toward a level's population requirement. Adults only, unless {@code levelCountsBabies}
	 * also counts babies -- a baby cannot hold a job, so it never counts toward the profession requirement
	 * (spec v4 mini-update M2).
	 */
	public static int population(VillageAnalyzer.Stats stats) {
		return stats.adultVillagers() + (LVConfig.get().levelCountsBabies ? stats.babies() : 0);
	}

	/** Distinct professions among the adults, nitwit and jobless excluded (ids without namespace, e.g. "librarian"). */
	private static Set<String> professionsOf(List<Villager> adults) {
		Set<String> professions = new HashSet<>();
		for (Villager villager : adults) {
			VillagerProfession profession = villager.getVillagerData().getProfession();
			if (profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT) {
				professions.add(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath());
			}
		}
		return professions;
	}

	/** The highest level whose requirements the village meets now (spec v4 mini-update M2: population counts babies). */
	public static int qualified(VillageAnalyzer.Stats stats) {
		Set<String> professions = professionsOf(stats.adults());
		int population = population(stats);
		int level = 0;
		for (LVConfig.LevelRequirement requirement : LVConfig.get().levelRequirements) {
			if (population < requirement.villagers || professions.size() < requirement.professions
					|| !professions.containsAll(requirement.services)) {
				break;
			}
			level++;
		}
		return level;
	}

	/** How far the village is from its next level, for status and summary logs (spec v4 mini-update M6). */
	public record Progress(int level, boolean maxed, int adults, int babies, int professions,
			int neededPopulation, int neededProfessions, List<String> missingServices) {
	}

	public static Progress progress(VillageRecord village, VillageAnalyzer.Stats stats) {
		int level = Math.max(0, village.getLevel());
		Set<String> professions = professionsOf(stats.adults());
		if (level >= MAX) {
			return new Progress(level, true, stats.adultVillagers(), stats.babies(), professions.size(), 0, 0, List.of());
		}
		LVConfig.LevelRequirement requirement = LVConfig.get().levelRequirements.get(level);
		List<String> missing = requirement.services.stream().filter(s -> !professions.contains(s)).collect(Collectors.toList());
		return new Progress(level, false, stats.adultVillagers(), stats.babies(), professions.size(),
				requirement.villagers, requirement.professions, missing);
	}

	/**
	 * Updates the level from fresh stats. The first computation is silent (an existing village is not "promoted");
	 * later rises post a {@link VillageEvents.LevelUp}.
	 */
	public static void update(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats) {
		if (!enabled()) {
			return;
		}
		int current = village.getLevel();
		int qualified = qualified(stats);
		if (current < 0) {
			village.setLevel(qualified);
		} else if (qualified > current) {
			village.setLevel(qualified);
			VillageEvents.LEVEL_UP.post(new VillageEvents.LevelUp(level, village, qualified, stats));
		} else if (qualified < current && LVConfig.get().levelCanDecrease) {
			village.setLevel(qualified);
		}
	}
}
