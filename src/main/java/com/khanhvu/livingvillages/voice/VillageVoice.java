package com.khanhvu.livingvillages.voice;

import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.society.BuildDecision;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.society.VillageLeader;
import com.khanhvu.livingvillages.society.VillageNeeds;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.work.ProfessionWork;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Villagers say short lines above their heads (spec v2-GĐ 6), picked from what really happens in the village. A line
 * is a vanilla text display riding the speaker; it is tagged and removed when it expires, and any tagged display
 * that loads without being tracked (e.g. after a crash) is removed at once.
 */
public final class VillageVoice {
	public static final String BUBBLE_TAG = "livingvillages_bubble";
	private static final ResourceLocation GUARD_ID = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");
	private static final int VERTICAL_RANGE = 16;
	private static final int GREETING_DISTANCE = 5;
	/** Recent births, deaths and buildings are talked about for one day. */
	private static final long RECENT_TICKS = 24000;
	private static final int MAX_LINES_PER_GROUP = 12;

	private record Bubble(UUID village, long expiresAt, ResourceKey<Level> dimension) {
	}

	/** Live bubbles by entity id. */
	private static final Map<UUID, Bubble> BUBBLES = new HashMap<>();

	private VillageVoice() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.getTags().contains(BUBBLE_TAG) && !BUBBLES.containsKey(entity.getUUID())) {
				entity.discard(); // left over from a crash or an unload: never keep floating text
			}
		});
		VillageEvents.REQUEST_FULFILLED.register(e -> {
			Villager leader = VillageLeader.getLeader(e.level(), e.village());
			if (leader == null || !LVConfig.get().voiceEnabled || leader.isVehicle()) {
				return;
			}
			Component line = line(e.allDone() ? "board_all_done" : "board_thanks", e.level().getRandom(), e.player() == null ? "" : e.player());
			if (line != null) {
				say(e.level(), e.village(), leader, line, e.level().getGameTime());
			}
		});
		VillageEvents.ZOMBIE_CURED.register(e -> {
			Villager cleric = e.cleric();
			if (cleric == null || !cleric.isAlive() || !LVConfig.get().voiceEnabled || cleric.isVehicle()
					|| e.level().getNearestPlayer(cleric, LVConfig.get().voiceRange) == null) {
				return;
			}
			Component line = line("work.cleric_cure", e.level().getRandom(), e.villager().getName().getString());
			if (line != null) {
				say(e.level(), e.village(), cleric, line, e.level().getGameTime());
			}
		});
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		long now = level.getGameTime();
		expire(level, now);
		if (Math.floorMod(now + village.getId().hashCode(), config.voiceIntervalTicks) != 0) {
			return;
		}
		long timeOfDay = level.getDayTime() % 24000L;
		boolean night = timeOfDay > 12500 && timeOfDay < 23500;
		if ((night && !village.isFestivalActive()) || activeBubbles(village) >= config.maxBubblesPerVillage) {
			return;
		}
		List<ServerPlayer> listeners = level.players().stream()
				.filter(p -> !p.isSpectator() && village.horizontalDistSqr(p.blockPosition()) <= sqr(VillageAnalyzer.areaRadius(village) + config.voiceRange))
				.toList();
		if (listeners.isEmpty()) {
			return;
		}
		Entity speaker = chooseSpeaker(level, village, listeners, config.voiceRange, level.getRandom());
		if (speaker == null) {
			return;
		}
		boolean nitwit = speaker instanceof Villager v && v.getVillagerData().getProfession() == VillagerProfession.NITWIT;
		double chance = config.voiceChance * (nitwit ? 2.0 : 1.0);
		if (level.getRandom().nextDouble() >= chance) {
			return;
		}
		Component line = chooseLine(level, village, speaker, listeners, now);
		if (line != null) {
			say(level, village, speaker, line, now);
		}
	}

	@Nullable
	private static Entity chooseSpeaker(ServerLevel level, VillageRecord village, List<ServerPlayer> listeners, int range, RandomSource random) {
		List<Entity> candidates = new ArrayList<>();
		Optional<EntityType<?>> guardType = BuiltInRegistries.ENTITY_TYPE.getOptional(GUARD_ID);
		for (ServerPlayer player : listeners) {
			AABB box = player.getBoundingBox().inflate(range, VERTICAL_RANGE, range);
			for (Entity entity : level.getEntities((Entity) null, box, e -> e.isAlive() && e.getPassengers().isEmpty()
					&& (e instanceof Villager || (guardType.isPresent() && e.getType() == guardType.get())))) {
				if (!(entity instanceof Villager villager) || !villager.isSleeping()) {
					candidates.add(entity);
				}
			}
		}
		return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
	}

	/**
	 * The most relevant groups first (festival, the leader's plans, own work, a recent birth or death, unmet needs,
	 * building news, greeting a nearby player, mood), and a random pick among the two most relevant.
	 */
	@Nullable
	private static Component chooseLine(ServerLevel level, VillageRecord village, Entity speaker, List<ServerPlayer> listeners, long now) {
		if (!(speaker instanceof Villager villager)) {
			return line("guard", level.getRandom());
		}
		List<Component> options = new ArrayList<>();
		RandomSource random = level.getRandom();
		if (village.isFestivalActive()) {
			add(options, line("festival", random));
		}
		if (villager.getUUID().equals(village.getLeaderUuid())) {
			add(options, leaderLine(level, village, random));
			if (village.getRequests().stream().anyMatch(r -> !r.isFulfilled())) {
				add(options, line("board", random)); // the leader reminds people of the board
			}
		}
		if (ProfessionWork.isWorking(villager.getUUID())) {
			String profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).getPath();
			add(options, line("work." + profession, random));
		}
		if (village.getRecentBirthName() != null && now - village.getRecentBirthTick() < RECENT_TICKS) {
			add(options, line("birth", random, village.getRecentBirthName()));
		}
		if (village.getRecentDeathName() != null && now - village.getRecentDeathTick() < RECENT_TICKS) {
			add(options, line("mourning", random, village.getRecentDeathName()));
		}
		VillageNeeds needs = village.getNeeds();
		if (needs != null) {
			int threshold = LVConfig.get().needThreshold;
			int lowest = Math.min(Math.min(needs.housing(), needs.food()), Math.min(needs.jobs(), needs.safety()));
			if (lowest < threshold) {
				String group = lowest == needs.food() ? "food_low" : lowest == needs.safety() ? "safety_low"
						: lowest == needs.housing() ? "housing_low" : "jobs_low";
				add(options, line(group, random));
			}
		}
		if (village.getProject() != null) {
			add(options, line("building", random));
		} else if (village.getLastBuildTick() != VillageRecord.NEVER && now - village.getLastBuildTick() < RECENT_TICKS) {
			add(options, line("new_building", random));
		}
		for (ServerPlayer player : listeners) {
			if (player.distanceToSqr(villager) <= GREETING_DISTANCE * GREETING_DISTANCE) {
				long timeOfDay = level.getDayTime() % 24000L;
				add(options, line(timeOfDay < 6000 ? "greeting_morning" : timeOfDay < 11000 ? "greeting_afternoon" : "greeting_evening", random));
				break;
			}
		}
		if (village.getMood() >= 0) {
			VillageMood.Level mood = VillageMood.Level.of(village.getMood());
			if (mood != VillageMood.Level.NORMAL) {
				add(options, line(mood == VillageMood.Level.HAPPY ? "mood_happy" : "mood_sad", random));
			}
		}
		add(options, line("idle", random));
		int pick = options.size() >= 2 ? random.nextInt(2) : 0;
		return options.isEmpty() ? null : options.get(pick);
	}

	/** "We will build a farm next" — the leader talks about the village plan. */
	@Nullable
	private static Component leaderLine(ServerLevel level, VillageRecord village, RandomSource random) {
		VillageNeeds needs = village.getNeeds();
		if (needs == null) {
			return null;
		}
		VillageAnalyzer.Stats stats = VillageAnalyzer.analyze(level, village);
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village));
		BuildDecision decision = BuildDecision.decide(level, village, stats, needs, buildings);
		if (!decision.builds()) {
			return null;
		}
		return line("leader", random, VillageSociety.buildingName(decision.kind(), decision.profession()).getString());
	}

	@Nullable
	private static Component line(String group, RandomSource random, Object... args) {
		List<String> keys = new ArrayList<>();
		for (int i = 1; i <= MAX_LINES_PER_GROUP; i++) {
			String key = "livingvillages.voice." + group + "." + i;
			if (!LVText.has(key)) {
				break;
			}
			keys.add(key);
		}
		return keys.isEmpty() ? null : LVText.tr(keys.get(random.nextInt(keys.size())), args);
	}

	private static void add(List<Component> options, @Nullable Component line) {
		if (line != null) {
			options.add(line);
		}
	}

	/** Spawns the bubble: a text display riding the speaker, removed after bubbleDurationTicks. */
	public static void say(ServerLevel level, VillageRecord village, Entity speaker, Component text, long now) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:text_display");
		tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
		tag.putString("billboard", "center");
		tag.putString("alignment", "center"); // vanilla always decodes it and logs an error when missing
		tag.putInt("background", 0x60000000);
		tag.putInt("line_width", 150);
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf(BUBBLE_TAG));
		tag.put("Tags", tags);
		// Riding the speaker already puts the text just above the head; no transformation needed.
		Entity bubble = EntityType.loadEntityRecursive(tag, level, entity -> {
			entity.moveTo(speaker.getX(), speaker.getY() + speaker.getBbHeight(), speaker.getZ());
			return entity;
		});
		if (bubble == null) {
			return;
		}
		BUBBLES.put(bubble.getUUID(), new Bubble(village.getId(), now + LVConfig.get().bubbleDurationTicks, level.dimension()));
		if (!level.addFreshEntity(bubble)) {
			BUBBLES.remove(bubble.getUUID());
			return;
		}
		bubble.startRiding(speaker, true);
	}

	private static void expire(ServerLevel level, long now) {
		for (Iterator<Map.Entry<UUID, Bubble>> it = BUBBLES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Bubble> entry = it.next();
			if (entry.getValue().dimension() == level.dimension() && entry.getValue().expiresAt() <= now) {
				Entity bubble = level.getEntity(entry.getKey());
				if (bubble != null) {
					bubble.discard();
				}
				it.remove(); // if it was unloaded, the load check removes it later
			}
		}
	}

	private static int activeBubbles(VillageRecord village) {
		int count = 0;
		for (Bubble bubble : BUBBLES.values()) {
			if (bubble.village().equals(village.getId())) {
				count++;
			}
		}
		return count;
	}

	/** Removes every live bubble of the level (server stopping). */
	public static void clear(ServerLevel level) {
		for (Iterator<Map.Entry<UUID, Bubble>> it = BUBBLES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Bubble> entry = it.next();
			if (entry.getValue().dimension() == level.dimension()) {
				Entity bubble = level.getEntity(entry.getKey());
				if (bubble != null) {
					bubble.discard();
				}
				it.remove();
			}
		}
	}

	private static double sqr(double value) {
		return value * value;
	}
}
