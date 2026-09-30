package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.work.ProfessionWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

/**
 * The four needs of a village, each 0–100 (100 = fully satisfied), measured cheaply from counts the game already
 * keeps (POIs, a few entity queries). Spec v2-GĐ 2.1.
 */
public record VillageNeeds(int housing, int food, int jobs, int safety) {
	/** Guard Villagers' guard, looked up by id so the mod never depends on its classes. */
	private static final ResourceLocation GUARD_ID = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");
	private static final int VERTICAL_RANGE = 24;
	/** Share of the adults that should have a free bed for full housing. */
	private static final double FREE_BED_SHARE = 0.2;

	public static VillageNeeds compute(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats) {
		LVConfig config = LVConfig.get();
		List<Villager> adults = stats.adults();
		int adultCount = adults.size();
		int radius = VillageAnalyzer.areaRadius(village);
		BlockPos bell = village.getBellPos();

		// Housing: no free bed = 0, free beds for 20% of the adults = 100.
		int wantedFreeBeds = Math.max(1, Mth.ceil(adultCount * FREE_BED_SHARE));
		int housing = percent(stats.freeBeds(), wantedFreeBeds);

		// Food: share of farmers compared to the target share.
		int farmers = 0;
		int unemployed = 0;
		for (Villager villager : adults) {
			VillagerProfession profession = villager.getVillagerData().getProfession();
			if (profession == VillagerProfession.FARMER) {
				farmers++;
			} else if (profession == VillagerProfession.NONE) {
				unemployed++;
			}
		}
		int food = adultCount == 0 ? 100 : percent(farmers, adultCount * config.farmersPerVillager);

		// Jobs: unemployed villagers against free job sites.
		long freeJobSites = level.getPoiManager().getCountInRange(h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), bell, radius,
				PoiManager.Occupancy.HAS_SPACE);
		int jobs = unemployed == 0 ? 100 : percent(freeJobSites, unemployed);

		// Safety: an undefended but peaceful village is neutral (50); enough defenders make it 100; recent attacks lower it.
		int defenders = countDefenders(level, bell, radius);
		double wantedDefenders = Math.max(1.0, adultCount * config.defendersPerVillager);
		// Armorers and weaponsmiths make the village sturdier (v2-GĐ 4).
		int base = 50 + percent(defenders, wantedDefenders) / 2 + ProfessionWork.bonuses(adults).safety();
		double attacks = AttackTracker.currentScore(village, level.getGameTime());
		int safety = Mth.clamp((int) Math.round(base - attacks * config.attackPenalty), 0, 100);

		return new VillageNeeds(housing, food, jobs, safety);
	}

	private static int percent(double have, double want) {
		if (want <= 0) {
			return 100;
		}
		return Mth.clamp((int) Math.round(have * 100.0 / want), 0, 100);
	}

	private static int countDefenders(ServerLevel level, BlockPos bell, int radius) {
		Optional<EntityType<?>> guardType = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_ID);
		AABB box = new AABB(bell).inflate(radius, VERTICAL_RANGE, radius);
		return level.getEntities((Entity) null, box, entity -> entity.isAlive()
				&& (entity instanceof IronGolem || (guardType.isPresent() && entity.getType() == guardType.get()))).size();
	}

	public int average() {
		return (housing + food + jobs + safety) / 4;
	}
}
