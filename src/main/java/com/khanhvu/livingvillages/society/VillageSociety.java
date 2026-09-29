package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The village "brain" (spec v2-GĐ 2): every manage interval, recomputes needs and mood and keeps the leader valid.
 * In this stage it only computes and shows; building decisions still follow the 0.1 rule (v2-GĐ 3 changes that).
 */
public final class VillageSociety {
	/** Bound on remembered villagers per village; the oldest entries go first. */
	private static final int MAX_KNOWN_VILLAGERS = 512;

	private VillageSociety() {
	}

	public static void register() {
		AttackTracker.register();
		VillageEvents.BUILDING_COMPLETED.register(e -> VillageMood.addEffect(e.village(), VillageMood.BUILDING_COMPLETED, e.level().getGameTime()));
		VillageEvents.DEATH.register(e -> VillageMood.addEffect(e.village(), VillageMood.VILLAGER_DIED, e.level().getGameTime()));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Villager villager && entity.level() instanceof ServerLevel level && LVConfig.get().needsEnabled) {
				VillageRegistry registry = VillageRegistry.get(level);
				VillageRecord village = registry.findContaining(villager.blockPosition());
				if (village != null && village.getKnownVillagers().containsKey(villager.getUUID())) {
					forget(village, villager.getUUID());
					registry.setDirty();
					VillageEvents.DEATH.post(new VillageEvents.Death(level, village, villager));
				}
			}
		});
		// Conversions (zombie, guard) discard the villager without a death: the leader must be replaced right away.
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			Entity.RemovalReason reason = entity.getRemovalReason();
			if (entity instanceof Villager && reason == Entity.RemovalReason.DISCARDED) {
				VillageRegistry registry = VillageRegistry.get(level);
				for (VillageRecord village : registry.getVillages()) {
					if (village.getKnownVillagers().containsKey(entity.getUUID())) {
						forget(village, entity.getUUID());
						registry.setDirty();
					}
				}
			}
		});
	}

	/** Recomputes needs, mood and leader. Returns the fresh stats for the caller's own decisions. */
	public static VillageAnalyzer.Stats update(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		long now = level.getGameTime();
		VillageAnalyzer.Stats stats = VillageAnalyzer.analyze(level, village);
		VillageAnalyzer.ensureVillageType(village, stats, registry);
		for (Villager villager : stats.adults()) {
			village.getKnownVillagers().putIfAbsent(villager.getUUID(), now);
		}
		trimKnown(village);
		VillageNeeds needs = VillageNeeds.compute(level, village, stats);
		village.setNeeds(needs);
		village.setMood(VillageMood.compute(village, needs, now));
		VillageLeader.update(level, village, stats.adults(), now);
		registry.setDirty();
		return stats;
	}

	/** What the leader wants and why, following the current building rule. */
	public static Component describeWish(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats) {
		LVConfig config = LVConfig.get();
		BuildProject project = village.getProject();
		if (project != null) {
			return LVText.tr("livingvillages.wish.building", project.getTemplateId().getPath(), project.getProgressPercent());
		}
		if (village.getHousesBuilt() >= config.maxHousesPerVillage) {
			return LVText.tr("livingvillages.wish.full", config.maxHousesPerVillage);
		}
		if (village.getFailedSiteAttempts() >= config.maxSiteFailures) {
			return LVText.tr("livingvillages.wish.no_site");
		}
		if (stats.adultVillagers() < 2) {
			return LVText.tr("livingvillages.wish.few_villagers");
		}
		if (stats.freeBeds() > config.freeBedThreshold) {
			return LVText.tr("livingvillages.wish.ok");
		}
		long now = level.getGameTime();
		if (village.getLastBuildTick() != VillageRecord.NEVER && now - village.getLastBuildTick() < config.cooldownTicks) {
			long left = village.getLastBuildTick() + config.cooldownTicks - now;
			return LVText.tr("livingvillages.wish.rest", left / 20);
		}
		return LVText.tr("livingvillages.wish.house");
	}

	/** "■■■■□□ 67" */
	public static String bar(int value) {
		int filled = Math.round(value * 6 / 100.0F);
		return "■".repeat(filled) + "□".repeat(6 - filled) + " " + value;
	}

	private static void forget(VillageRecord village, UUID id) {
		village.getKnownVillagers().remove(id);
		village.getTitledBaseNames().remove(id);
		if (id.equals(village.getLeaderUuid())) {
			village.setLeaderGone(true);
		}
	}

	private static void trimKnown(VillageRecord village) {
		Map<UUID, Long> known = village.getKnownVillagers();
		for (Iterator<UUID> it = known.keySet().iterator(); known.size() > MAX_KNOWN_VILLAGERS && it.hasNext(); ) {
			it.next();
			it.remove();
		}
	}
}
