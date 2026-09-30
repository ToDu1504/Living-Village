package com.khanhvu.livingvillages.identity;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.village.VillageType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Village identity (spec v2-GĐ 5): the village name, households with surnames, names for villagers and guards,
 * and the title shown when a player walks into a village.
 */
public final class VillageIdentity {
	private static final ResourceLocation GUARD_ID = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");
	/** Beds this close to a household anchor belong to that household (vanilla houses have no recorded bounds). */
	private static final int HOUSEHOLD_RADIUS = 8;
	private static final int VERTICAL_RANGE = 24;
	private static final String SEPARATOR = "\t";

	/** Last village each player was in, and when they were last greeted per village. Not saved. */
	private static final Map<UUID, UUID> PLAYER_VILLAGE = new HashMap<>();
	private static final Map<String, Long> LAST_GREETING = new HashMap<>();

	private VillageIdentity() {
	}

	/** Gives the village a name the first time (stays until /livingvillages rename). */
	public static void ensureName(VillageRecord village, VillageType type, ServerLevel level) {
		if (village.getName() == null) {
			village.setName(NamePool.randomVillageName(type, level.getRandom()));
		}
	}

	/** The village name, or its bell position before it has one. */
	public static String displayName(VillageRecord village) {
		BlockPos bell = village.getBellPos();
		return village.getName() != null ? village.getName() : bell.getX() + ", " + bell.getZ();
	}

	/** A new house gets its own household and surname right away. */
	public static void onHouseBuilt(ServerLevel level, VillageRecord village, BoundingBox footprint) {
		village.getHouseholds().put(footprint.getCenter().asLong(), newSurname(village, level));
	}

	/**
	 * Names every villager (babies too) that has no name: given name, plus the surname of its household once it has
	 * a bed. Names set by players or other mods are never touched; the leader's title is handled by the leader code.
	 */
	public static void nameVillagers(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		int radius = VillageAnalyzer.areaRadius(village);
		AABB area = new AABB(village.getBellPos()).inflate(radius, VERTICAL_RANGE, radius);
		if (config.nameVillagers) {
			for (Villager villager : level.getEntitiesOfClass(Villager.class, area, Entity::isAlive)) {
				nameVillager(level, village, villager);
			}
		}
		if (config.nameGuards) {
			Optional<EntityType<?>> guardType = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_ID);
			if (guardType.isPresent()) {
				for (Entity guard : level.getEntities((Entity) null, area, e -> e.isAlive() && e.getType() == guardType.get() && !e.hasCustomName())) {
					guard.setCustomName(LVText.tr("livingvillages.guard.title", NamePool.randomGivenName(level.getRandom())));
				}
			}
		}
	}

	/** Names one villager right away, e.g. one just cured, so it is talked about by name. */
	public static void nameNow(ServerLevel level, VillageRecord village, Villager villager) {
		if (LVConfig.get().nameVillagers) {
			nameVillager(level, village, villager);
		}
	}

	private static void nameVillager(ServerLevel level, VillageRecord village, Villager villager) {
		UUID id = villager.getUUID();
		if (village.getTitledBaseNames().containsKey(id)) {
			return; // the leader: title handled elsewhere
		}
		String stored = village.getGivenNames().get(id);
		String given = stored == null ? null : stored.split(SEPARATOR, -1)[0];
		String surname = stored == null ? "" : stored.split(SEPARATOR, -1)[1];
		Component current = villager.getCustomName();
		if (current != null && (stored == null || !current.getString().equals(fullName(given, surname)))) {
			village.getGivenNames().remove(id); // named by a player or another mod
			return;
		}
		if (given == null) {
			given = NamePool.randomGivenName(villager.getRandom());
		}
		if (surname.isEmpty()) {
			String household = surnameFor(level, village, villager);
			if (household != null) {
				surname = household;
			}
		}
		String name = fullName(given, surname);
		if (current == null || !current.getString().equals(name)) {
			villager.setCustomName(Component.literal(name));
			villager.setCustomNameVisible(false);
		}
		village.getGivenNames().put(id, given + SEPARATOR + surname);
	}

	/** "Trần Minh" / "Minh Tran": order comes from the language file. */
	public static String fullName(String given, String surname) {
		return surname.isEmpty() ? given : LVText.format("livingvillages.name.full", given, surname);
	}

	/** Surname of the household of the villager's bed, creating the household if needed; null without a bed. */
	@Nullable
	private static String surnameFor(ServerLevel level, VillageRecord village, Villager villager) {
		Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isEmpty() || home.get().dimension() != level.dimension()) {
			return null;
		}
		BlockPos bed = home.get().pos();
		for (BoundingBox plot : village.getPlots()) {
			if (plot.isInside(bed)) {
				return village.getHouseholds().computeIfAbsent(plot.getCenter().asLong(), k -> newSurname(village, level));
			}
		}
		long nearest = 0;
		double nearestDist = Double.MAX_VALUE;
		for (long anchor : village.getHouseholds().keySet()) {
			double dist = BlockPos.of(anchor).distSqr(bed);
			if (dist < nearestDist) {
				nearestDist = dist;
				nearest = anchor;
			}
		}
		if (nearestDist <= HOUSEHOLD_RADIUS * HOUSEHOLD_RADIUS) {
			return village.getHouseholds().get(nearest);
		}
		String surname = newSurname(village, level);
		village.getHouseholds().put(bed.asLong(), surname);
		return surname;
	}

	/** Surname of the household nearest to {@code pos} (e.g. where a baby was born), or null if none is close. */
	@Nullable
	public static String surnameNear(VillageRecord village, BlockPos pos) {
		for (BoundingBox plot : village.getPlots()) {
			if (plot.inflatedBy(HOUSEHOLD_RADIUS).isInside(pos)) {
				String surname = village.getHouseholds().get(plot.getCenter().asLong());
				if (surname != null) {
					return surname;
				}
			}
		}
		String nearest = null;
		double nearestDist = 2.0 * HOUSEHOLD_RADIUS * 2.0 * HOUSEHOLD_RADIUS;
		for (Map.Entry<Long, String> household : village.getHouseholds().entrySet()) {
			double dist = BlockPos.of(household.getKey()).distSqr(pos);
			if (dist <= nearestDist) {
				nearestDist = dist;
				nearest = household.getValue();
			}
		}
		return nearest;
	}

	/** A surname not yet used in the village when possible. */
	private static String newSurname(VillageRecord village, ServerLevel level) {
		Set<String> used = new HashSet<>(village.getHouseholds().values());
		for (int i = 0; i < 8; i++) {
			String surname = NamePool.randomSurname(level.getRandom());
			if (!used.contains(surname)) {
				return surname;
			}
		}
		return NamePool.randomSurname(level.getRandom());
	}

	/** Every 20 ticks: title for players who just walked into a village (once per greetingCooldownTicks). */
	public static void greetPlayers(ServerLevel level, VillageRegistry registry) {
		LVConfig config = LVConfig.get();
		long now = level.getGameTime();
		for (ServerPlayer player : level.players()) {
			VillageRecord village = registry.findContaining(player.blockPosition());
			UUID previous = PLAYER_VILLAGE.put(player.getUUID(), village == null ? null : village.getId());
			if (village == null || village.getId().equals(previous) || !config.showEntryTitle) {
				continue;
			}
			String key = player.getUUID() + "/" + village.getId();
			Long last = LAST_GREETING.get(key);
			if (last != null && now - last < config.greetingCooldownTicks) {
				continue;
			}
			LAST_GREETING.put(key, now);
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(displayName(village))));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle(village)));
		}
	}

	/** "Thị trấn · 24 dân · Hạnh phúc", leaving out what is not known. */
	private static Component subtitle(VillageRecord village) {
		StringBuilder text = new StringBuilder();
		if (VillageLevel.enabled() && village.getLevel() >= 0) {
			text.append(LVText.format(VillageLevel.langKey(village.getLevel())));
		}
		if (village.getLastAdultCount() > 0) {
			if (!text.isEmpty()) {
				text.append(" · ");
			}
			text.append(LVText.format("livingvillages.greeting.villagers", village.getLastAdultCount()));
		}
		if (village.getMood() >= 0) {
			if (!text.isEmpty()) {
				text.append(" · ");
			}
			text.append(LVText.format(VillageMood.Level.of(village.getMood()).langKey()));
		}
		return Component.literal(text.toString());
	}

	/** Forgets per-player greeting state (server stop). */
	public static void clear() {
		PLAYER_VILLAGE.clear();
		LAST_GREETING.clear();
	}
}
