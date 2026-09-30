package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.wall.CitySites;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The leader's building decision (spec v2-GĐ 3.2): the first unmet need that has a matching template decides what
 * to build. {@code kind == null} means "build nothing"; {@code reasonKey} explains why in both cases.
 */
public record BuildDecision(@Nullable BuildingKind kind, @Nullable ResourceLocation profession, String reasonKey) {
	/** Professions that tend animals (v2-GĐ 4) and the animal each one keeps. */
	public static final Map<VillagerProfession, EntityType<? extends Animal>> ANIMAL_KEEPERS = Map.of(
			VillagerProfession.SHEPHERD, EntityType.SHEEP,
			VillagerProfession.BUTCHER, EntityType.PIG,
			VillagerProfession.LEATHERWORKER, EntityType.COW,
			VillagerProfession.FLETCHER, EntityType.CHICKEN);
	private static final int VERTICAL_RANGE = 24;

	public boolean builds() {
		return kind != null;
	}

	public static BuildDecision decide(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats, VillageNeeds needs,
			List<BuildingTemplate> buildings) {
		// The level limit comes first: an unmet need cannot override it.
		if (village.getHousesBuilt() >= VillageLevel.buildLimit(village)) {
			return new BuildDecision(null, null, "livingvillages.reason.limit");
		}
		int threshold = LVConfig.get().needThreshold;
		// A city whose walls are full builds no more houses or workshops until an outer ring gives room (v3-GĐ 3).
		boolean full = CitySites.isFull(village);
		boolean fullBlocked = false;
		if (needs.housing() < threshold && has(buildings, BuildingKind.HOUSE)) {
			if (!full) {
				return new BuildDecision(BuildingKind.HOUSE, null, "livingvillages.reason.housing");
			}
			fullBlocked = true;
		}
		// A composter nobody works at yet means the village lacks people, not farms: another farm would not help.
		boolean farmWaiting = false;
		if (needs.food() < threshold && has(buildings, BuildingKind.FARM)) {
			if (!hasFreeFarm(level, village)) {
				return new BuildDecision(BuildingKind.FARM, null, "livingvillages.reason.food");
			}
			farmWaiting = true;
		}
		if (needs.jobs() < threshold) {
			ResourceLocation profession = rarestWorkshopProfession(stats.adults(), buildings);
			if (profession != null && !full) {
				return new BuildDecision(BuildingKind.WORKSHOP, profession, "livingvillages.reason.jobs");
			}
			fullBlocked |= profession != null;
		}
		if (has(buildings, BuildingKind.PEN) && needsPen(level, village, stats.adults())) {
			return new BuildDecision(BuildingKind.PEN, null, "livingvillages.reason.pen");
		}
		return new BuildDecision(null, null, fullBlocked ? "livingvillages.reason.city_full"
				: farmWaiting ? "livingvillages.reason.food_wait" : "livingvillages.reason.ok");
	}

	/** Templates matching the decision (workshops of the chosen profession only). */
	public List<BuildingTemplate> candidates(List<BuildingTemplate> buildings) {
		return buildings.stream()
				.filter(b -> b.kind() == kind && (profession == null || profession.equals(b.profession())))
				.toList();
	}

	private static boolean has(List<BuildingTemplate> buildings, BuildingKind kind) {
		return !BuildingTemplateProvider.ofKind(buildings, kind).isEmpty();
	}

	private static boolean hasFreeFarm(ServerLevel level, VillageRecord village) {
		return level.getPoiManager().getCountInRange(h -> h.is(PoiTypes.FARMER), village.getBellPos(), VillageAnalyzer.areaRadius(village),
				PoiManager.Occupancy.HAS_SPACE) > 0;
	}

	/** Among the professions that have a workshop template, the one with the fewest villagers (0 if the village has none). */
	@Nullable
	private static ResourceLocation rarestWorkshopProfession(List<Villager> adults, List<BuildingTemplate> buildings) {
		Map<ResourceLocation, Integer> counts = new HashMap<>();
		for (Villager villager : adults) {
			counts.merge(BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()), 1, Integer::sum);
		}
		return BuildingTemplateProvider.ofKind(buildings, BuildingKind.WORKSHOP).stream()
				.map(BuildingTemplate::profession)
				.filter(Objects::nonNull)
				.distinct()
				.min(Comparator.comparingInt((ResourceLocation p) -> counts.getOrDefault(p, 0)).thenComparing(ResourceLocation::toString))
				.orElse(null);
	}

	/** The village has animal keepers but none of their animals around. */
	private static boolean needsPen(ServerLevel level, VillageRecord village, List<Villager> adults) {
		Set<EntityType<? extends Animal>> wanted = new HashSet<>();
		for (Villager villager : adults) {
			EntityType<? extends Animal> animal = ANIMAL_KEEPERS.get(villager.getVillagerData().getProfession());
			if (animal != null) {
				wanted.add(animal);
			}
		}
		if (wanted.isEmpty()) {
			return false;
		}
		int radius = VillageAnalyzer.areaRadius(village);
		AABB box = new AABB(village.getBellPos()).inflate(radius, VERTICAL_RANGE, radius);
		return level.getEntitiesOfClass(Animal.class, box, a -> wanted.contains(a.getType())).isEmpty();
	}

	/** Animal type to put in a new pen: the first keeper profession present whose animal is missing, else sheep. */
	public static EntityType<? extends Animal> animalForPen(List<Villager> adults) {
		for (Villager villager : adults) {
			EntityType<? extends Animal> animal = ANIMAL_KEEPERS.get(villager.getVillagerData().getProfession());
			if (animal != null) {
				return animal;
			}
		}
		return EntityType.SHEEP;
	}
}
