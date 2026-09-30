package com.khanhvu.livingvillages.chronicle;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The village chronicle (spec v2-GĐ 7): listens to village events, keeps the latest {@code chronicleMaxEntries}
 * entries, tells nearby players (chat for big events, action bar for small ones), adds graves for named villagers
 * and guards, and tracks raids. Needs {@code needsEnabled}, since the village's villagers are known through it.
 */
public final class Chronicle {
	public enum Notice { NONE, SMALL, BIG }

	private static final ResourceLocation GUARD_ID = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");
	/** A guard this close to a villager discarded in the same tick replaced that villager (Guard Villagers conversion). */
	private static final double GUARD_MATCH_DISTANCE = 2.0;

	/** Raid in progress per village, to report its outcome when it ends. Not saved. */
	private static final Map<UUID, Raid> RAIDS = new HashMap<>();

	private record Gone(UUID village, String name, BlockPos pos, long tick) {
	}

	/** Villagers discarded and guards loaded during the current tick, matched to detect "joined the guards". */
	private static final List<Gone> GONE = new ArrayList<>();
	private static final List<Entity> NEW_GUARDS = new ArrayList<>();
	private static long matchTick = Long.MIN_VALUE;

	private Chronicle() {
	}

	public static boolean enabled() {
		LVConfig config = LVConfig.get();
		return config.needsEnabled && config.chronicleEnabled;
	}

	/**
	 * Entity listeners that must run before {@link VillageSociety#register()}'s, which forget a discarded villager
	 * together with its name.
	 */
	public static void registerEarly() {
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager villager && entity.getRemovalReason() == Entity.RemovalReason.DISCARDED && enabled()) {
				VillageRecord village = VillageRegistry.get(level).findContaining(villager.blockPosition());
				if (village != null && village.getKnownVillagers().containsKey(villager.getUUID())) {
					resetMatching(level);
					GONE.add(new Gone(village.getId(), VillageSociety.baseName(village, villager), villager.blockPosition(), level.getGameTime()));
					matchGuards(level);
				}
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (isGuard(entity) && enabled()) {
				resetMatching(level);
				NEW_GUARDS.add(entity);
				matchGuards(level);
			}
		});
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, keepEquipment) -> {
			if (!(previous instanceof Villager villager) || !(previous.level() instanceof ServerLevel level) || !enabled()) {
				return;
			}
			VillageRecord village = VillageRegistry.get(level).findContaining(villager.blockPosition());
			if (village == null) {
				return;
			}
			String name = VillageSociety.baseName(village, villager);
			if (converted instanceof ZombieVillager) {
				if (village.getTitledBaseNames().containsKey(villager.getUUID())) {
					converted.setCustomName(Component.literal(name)); // the zombie is no chief
				}
				add(level, village, Notice.SMALL, "livingvillages.chronicle.zombified", name);
			} else if (isGuard(converted)) {
				joinedGuards(level, village, name, converted);
			}
		});
	}

	public static void register() {
		VillageEvents.BIRTH.register(e -> {
			String name = e.baby().getName().getString();
			String surname = VillageIdentity.surnameNear(e.village(), e.baby().blockPosition());
			if (surname != null) {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.birth_household", surname, name);
			} else {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.birth", name);
			}
		});
		VillageEvents.DEATH.register(e -> onDeath(e.level(), e.village(), VillageSociety.baseName(e.village(), e.villager()),
				professionKey(e.villager().getVillagerData().getProfession()), e.source(), e.villager().hasCustomName()));
		VillageEvents.ZOMBIE_CURED.register(e -> {
			String name = e.villager().getName().getString();
			if (e.cleric() != null) {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.cured_by", VillageSociety.baseName(e.village(), e.cleric()), name);
			} else {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.cured", name);
			}
		});
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			if (e.kind() == null) {
				return;
			}
			BuildingTemplate building = BuildingTemplateProvider.findById(e.level(), VillageTicker.villageType(e.village()), e.templateId());
			if (e.kind() == BuildingKind.WORKSHOP && building != null && building.profession() != null) {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.workshop",
						ChronicleEntry.keyArg("livingvillages.profession." + building.profession().getPath()));
			} else {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.building", ChronicleEntry.keyArg(e.kind().langKey()));
			}
		});
		VillageEvents.LEVEL_UP.register(e -> add(e.level(), e.village(), Notice.BIG, "livingvillages.chronicle.level_up",
				ChronicleEntry.keyArg(VillageLevel.langKey(e.newLevel()))));
		VillageEvents.FESTIVAL.register(e -> add(e.level(), e.village(), Notice.BIG, "livingvillages.chronicle.festival",
				ChronicleEntry.keyArg(e.nameKey())));
		VillageEvents.REQUEST_FULFILLED.register(e -> {
			String item = ChronicleEntry.itemArg(BuiltInRegistries.ITEM.get(e.item()));
			if (e.player() != null) {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.delivered", e.player(), String.valueOf(e.count()), item);
			} else {
				add(e.level(), e.village(), Notice.SMALL, "livingvillages.chronicle.delivered_someone", String.valueOf(e.count()), item);
			}
		});
		VillageEvents.LEADER_CHANGED.register(e -> {
			if (e.newLeader() != null) {
				add(e.level(), e.village(), Notice.NONE, "livingvillages.chronicle.leader", VillageSociety.baseName(e.village(), e.newLeader()));
			}
		});
		// Named guards who fall get an entry and a grave like villagers (spec §3).
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (isGuard(entity) && entity.hasCustomName() && entity.level() instanceof ServerLevel level && enabled()) {
				VillageRecord village = VillageRegistry.get(level).findContaining(entity.blockPosition());
				if (village != null) {
					onDeath(level, village, entity.getCustomName().getString(), "livingvillages.chronicle.guard_role", source, true);
				}
			}
		});
	}

	/** At the manage interval of a village near a player: raid outcome and the librarians' books. */
	public static void manage(ServerLevel level, VillageRecord village, VillageAnalyzer.Stats stats) {
		trackRaid(level, village);
		ChronicleBook.update(level, village, stats.adults());
	}

	/** Adds an entry, trims the chronicle and tells nearby players. */
	public static void add(ServerLevel level, VillageRecord village, Notice notice, String key, String... args) {
		if (!enabled()) {
			return;
		}
		LVConfig config = LVConfig.get();
		ChronicleEntry entry = new ChronicleEntry(level.getDayTime() / 24000L + 1, key, List.of(args));
		List<ChronicleEntry> chronicle = village.getChronicle();
		chronicle.add(entry);
		while (chronicle.size() > config.chronicleMaxEntries) {
			chronicle.remove(0);
		}
		village.setChronicleDirty(true);
		VillageRegistry.get(level).setDirty();
		LivingVillages.debug("Village {} chronicle: {}", village.getId(), entry.text());
		if (config.announceEvents && notice != Notice.NONE) {
			announce(level, village, notice, entry);
		}
	}

	private static void onDeath(ServerLevel level, VillageRecord village, String name, String roleKey, DamageSource source, boolean named) {
		Entity killer = source.getEntity();
		if (killer != null) {
			String killerArg = killer instanceof Player player ? player.getName().getString() : ChronicleEntry.entityArg(killer.getType());
			add(level, village, Notice.SMALL, "livingvillages.chronicle.death_killed", name, ChronicleEntry.keyArg(roleKey), killerArg);
		} else {
			add(level, village, Notice.SMALL, "livingvillages.chronicle.death", name, ChronicleEntry.keyArg(roleKey));
		}
		if (named && LVConfig.get().gravesEnabled && enabled()) {
			Graveyard.addGrave(level, village, name, LVText.tr(roleKey), level.getDayTime() / 24000L + 1);
		}
	}

	private static void joinedGuards(ServerLevel level, VillageRecord village, String name, Entity guard) {
		if (!guard.hasCustomName() && LVConfig.get().nameGuards) {
			guard.setCustomName(LVText.tr("livingvillages.guard.title", name)); // the new guard keeps the villager's name
		}
		add(level, village, Notice.SMALL, "livingvillages.chronicle.joined_guards", name);
	}

	private static void resetMatching(ServerLevel level) {
		if (matchTick != level.getGameTime()) {
			matchTick = level.getGameTime();
			GONE.clear();
			NEW_GUARDS.clear();
		}
	}

	private static void matchGuards(ServerLevel level) {
		for (Iterator<Gone> it = GONE.iterator(); it.hasNext(); ) {
			Gone gone = it.next();
			for (Iterator<Entity> guards = NEW_GUARDS.iterator(); guards.hasNext(); ) {
				Entity guard = guards.next();
				if (guard.level() == level && guard.position().closerThan(gone.pos().getBottomCenter(), GUARD_MATCH_DISTANCE)) {
					VillageRecord village = VillageRegistry.get(level).getVillages().stream()
							.filter(v -> v.getId().equals(gone.village())).findFirst().orElse(null);
					if (village != null) {
						joinedGuards(level, village, gone.name(), guard);
					}
					it.remove();
					guards.remove();
					break;
				}
			}
		}
	}

	private static void trackRaid(ServerLevel level, VillageRecord village) {
		Raid current = level.getRaidAt(village.getBellPos());
		Raid tracked = RAIDS.get(village.getId());
		if (tracked != null && (tracked != current || tracked.isOver() || tracked.isStopped())) {
			if (tracked.isVictory()) {
				add(level, village, Notice.BIG, "livingvillages.chronicle.raid_won");
			} else if (tracked.isLoss()) {
				add(level, village, Notice.BIG, "livingvillages.chronicle.raid_lost");
			}
			RAIDS.remove(village.getId());
			tracked = null;
		}
		if (tracked == null && current != null && !current.isOver() && !current.isStopped()) {
			RAIDS.put(village.getId(), current);
		}
	}

	/** Big events in chat for players within activeRange; small ones on the action bar of players in the village. */
	private static void announce(ServerLevel level, VillageRecord village, Notice notice, ChronicleEntry entry) {
		double range = notice == Notice.BIG ? LVConfig.get().activeRange : VillageAnalyzer.areaRadius(village);
		Component message = notice == Notice.BIG
				? LVText.compose("livingvillages.chronicle.announce", VillageIdentity.displayName(village), entry.message())
				: entry.message();
		for (ServerPlayer player : level.players()) {
			if (village.horizontalDistSqr(player.blockPosition()) <= range * range) {
				if (notice == Notice.BIG) {
					player.sendSystemMessage(message);
				} else {
					player.displayClientMessage(message, true);
				}
			}
		}
	}

	private static String professionKey(VillagerProfession profession) {
		return "livingvillages.profession." + BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath();
	}

	private static boolean isGuard(Entity entity) {
		Optional<EntityType<?>> guardType = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_ID);
		return guardType.isPresent() && entity.getType() == guardType.get();
	}

	/** Forgets raids in progress (server stop). */
	public static void clear() {
		RAIDS.clear();
		GONE.clear();
		NEW_GUARDS.clear();
	}
}
