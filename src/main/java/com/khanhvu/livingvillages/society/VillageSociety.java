package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.work.ProfessionWork;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The village "brain" (spec v2-GĐ 2): every manage interval, recomputes needs and mood and keeps the leader valid.
 * Also keeps the village level; the building decision itself is {@link BuildDecision}.
 */
public final class VillageSociety {
	/** Bound on remembered villagers per village; the oldest entries go first. */
	private static final int MAX_KNOWN_VILLAGERS = 512;
	private static final int BIRTH_VERTICAL_RANGE = 24;

	private VillageSociety() {
	}

	public static void register() {
		AttackTracker.register();
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			VillageMood.addEffect(e.village(), VillageMood.BUILDING_COMPLETED, e.level().getGameTime());
			if (e.kind() == BuildingKind.HOUSE) {
				VillageIdentity.onHouseBuilt(e.level(), e.village(), e.footprint());
			}
		});
		VillageEvents.DEATH.register(e -> {
			VillageMood.addEffect(e.village(), VillageMood.VILLAGER_DIED, e.level().getGameTime());
			e.village().setRecentDeath(baseName(e.village(), e.villager()), e.level().getGameTime());
		});
		VillageEvents.BIRTH.register(e -> {
			e.village().setRecentBirth(e.baby().getName().getString(), e.level().getGameTime());
			LivingVillages.debug("Village {}: {} was born", e.village().getId(), e.baby().getName().getString());
		});
		VillageEvents.REQUEST_FULFILLED.register(e -> VillageMood.addEffect(e.village(), VillageMood.REQUEST_FULFILLED, e.level().getGameTime()));
		VillageEvents.ZOMBIE_CURED.register(e -> {
			VillageIdentity.nameNow(e.level(), e.village(), e.villager()); // before the cleric's words, which use the name
			VillageMood.addEffect(e.village(), VillageMood.ZOMBIE_CURED, e.level().getGameTime());
			LivingVillages.debug("Village {}: {} was cured", e.village().getId(), e.villager().getName().getString());
		});
		VillageEvents.LEVEL_UP.register(e -> {
			VillageMood.addEffect(e.village(), VillageMood.LEVEL_UP, e.level().getGameTime());
			announceLevelUp(e.level(), e.village(), e.newLevel());
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Villager villager && entity.level() instanceof ServerLevel level && LVConfig.get().needsEnabled) {
				VillageRegistry registry = VillageRegistry.get(level);
				VillageRecord village = registry.findContaining(villager.blockPosition());
				if (village != null && village.getKnownVillagers().containsKey(villager.getUUID())) {
					VillageEvents.DEATH.post(new VillageEvents.Death(level, village, villager, source));
					forget(village, villager.getUUID());
					registry.setDirty();
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
		village.setBonuses(ProfessionWork.bonuses(stats.adults()));
		VillageNeeds needs = VillageNeeds.compute(level, village, stats);
		village.setNeeds(needs);
		village.setMood(VillageMood.compute(village, needs, now));
		VillageLevel.update(level, village, stats);
		village.setLastAdultCount(stats.adults().size());
		VillageIdentity.ensureName(village, VillageTicker.villageType(village), level);
		VillageIdentity.nameVillagers(level, village); // before the leader, so the title goes on the full name
		detectBirths(level, village); // after naming, so the baby is talked about by name
		VillageLeader.update(level, village, stats.adults(), now);
		registry.setDirty();
		return stats;
	}

	/** What the leader wants and why (spec v2-GĐ 3.2), e.g. "build a farm (more farmers needed)". */
	public static Component describeWish(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats, VillageNeeds needs) {
		LVConfig config = LVConfig.get();
		BuildProject project = village.getProject();
		if (project != null) {
			return LVText.tr("livingvillages.wish.building", project.getTemplateId().getPath(), project.getProgressPercent());
		}
		if (village.getFailedSiteAttempts() >= config.maxSiteFailures) {
			return LVText.tr("livingvillages.wish.no_site");
		}
		if (stats.adultVillagers() < 2) {
			return LVText.tr("livingvillages.wish.few_villagers");
		}
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village));
		BuildDecision decision = BuildDecision.decide(level, village, stats, needs, buildings);
		Component reason = LVText.tr(decision.reasonKey());
		if (!decision.builds()) {
			return LVText.tr("livingvillages.wish.nothing", reason);
		}
		Component what = buildingName(decision.kind(), decision.profession());
		long now = level.getGameTime();
		if (village.getLastBuildTick() != VillageRecord.NEVER && now - village.getLastBuildTick() < config.cooldownTicks
				&& !village.isSkipCooldown()) {
			long left = village.getLastBuildTick() + config.cooldownTicks - now;
			return LVText.tr("livingvillages.wish.build_after_rest", what, reason, left / 20);
		}
		return LVText.tr("livingvillages.wish.build", what, reason);
	}

	/** "a house", "a farm", "a workshop for a librarian"... */
	public static Component buildingName(BuildingKind kind, @Nullable ResourceLocation profession) {
		if (kind == BuildingKind.WORKSHOP && profession != null) {
			return LVText.tr("livingvillages.kind.workshop_for", LVText.tr("livingvillages.profession." + profession.getPath()));
		}
		return LVText.tr(kind.langKey());
	}

	/** "■■■■□□ 67" */
	public static String bar(int value) {
		int filled = Math.round(value * 6 / 100.0F);
		return "■".repeat(filled) + "□".repeat(6 - filled) + " " + value;
	}

	/** Title for the players near the village (spec v2-GĐ 3.3). */
	private static void announceLevelUp(ServerLevel level, VillageRecord village, int newLevel) {
		Component title = LVText.tr("livingvillages.levelup.title", LVText.tr(VillageLevel.langKey(newLevel)));
		BlockPos bell = village.getBellPos();
		Component subtitle = LVText.tr("livingvillages.levelup.subtitle", bell.getX() + ", " + bell.getZ());
		double range = LVConfig.get().activeRange;
		for (ServerPlayer player : level.players()) {
			if (village.horizontalDistSqr(player.blockPosition()) <= range * range) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
				player.connection.send(new ClientboundSetTitleTextPacket(title));
				player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			}
		}
		LivingVillages.debug("Village {} reached level {}", village.getId(), newLevel);
	}

	/**
	 * Babies born since the previous update: a baby's age starts at -24000 and rises by one per tick, so a baby still
	 * within one manage interval of that is new. Stateless, and existing babies are never counted as births.
	 */
	private static void detectBirths(ServerLevel level, VillageRecord village) {
		int radius = VillageAnalyzer.areaRadius(village);
		int window = LVConfig.get().manageIntervalTicks;
		AABB area = new AABB(village.getBellPos()).inflate(radius, BIRTH_VERTICAL_RANGE, radius);
		for (Villager baby : level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && v.isBaby()
				&& v.getAge() < AgeableMob.BABY_START_AGE + window)) {
			VillageEvents.BIRTH.post(new VillageEvents.Birth(level, village, baby));
		}
	}

	/** The villager's name without the leader title. */
	public static String baseName(VillageRecord village, Villager villager) {
		String base = village.getTitledBaseNames().get(villager.getUUID());
		return base != null ? base : villager.getName().getString();
	}

	private static void forget(VillageRecord village, UUID id) {
		village.getKnownVillagers().remove(id);
		village.getTitledBaseNames().remove(id);
		village.getGivenNames().remove(id);
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
