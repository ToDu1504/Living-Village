package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.NamePool;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The village leader (spec v2-GĐ 2.3): the adult with the highest profession level, titled "Chief <name>" (visible
 * when looked at). A leader stays leader until they die, are converted (zombie, guard) or are away for
 * {@code leaderAbsentTicks}; a stronger villager does not take over.
 * <p>
 * Villagers with a profession come first, and a jobless leader gives way as soon as someone with a profession
 * can lead: vanilla closes the trading screen of jobless villagers every tick, and the material board (v2-GĐ 9)
 * trades through the leader.
 */
public final class VillageLeader {
	private static final String TITLE_KEY = "livingvillages.leader.title";

	private VillageLeader() {
	}

	/** Keeps the village's leader valid and titled. {@code adults} are the loaded adult villagers of the village. */
	public static void update(ServerLevel level, VillageRecord village, List<Villager> adults, long now) {
		UUID leaderId = village.getLeaderUuid();
		Villager leader = leaderId == null ? null : findIn(adults, leaderId);
		if (leader != null) {
			village.setLeaderLastSeenTick(now);
		}
		boolean absentTooLong = leaderId != null && leader == null
				&& now - village.getLeaderLastSeenTick() > LVConfig.get().leaderAbsentTicks;
		boolean joblessLeader = leader != null && !hasJob(leader)
				&& adults.stream().anyMatch(v -> hasJob(v) && isCandidate(village, v));
		if (leaderId == null || village.isLeaderGone() || absentTooLong || joblessLeader) {
			Villager chosen = choose(village, adults, now);
			village.setLeaderGone(false);
			if (chosen != null || leaderId != null) {
				replace(level, village, leader, chosen, now);
			}
		} else if (leader != null) {
			applyTitle(village, leader);
		}
		// Former leaders that were unloaded when replaced lose their title once seen again.
		for (Villager villager : adults) {
			if (!villager.getUUID().equals(village.getLeaderUuid()) && village.getTitledBaseNames().containsKey(villager.getUUID())) {
				removeTitle(village, villager);
			}
		}
	}

	/** The loaded leader entity, or null. */
	@Nullable
	public static Villager getLeader(ServerLevel level, VillageRecord village) {
		UUID id = village.getLeaderUuid();
		return id != null && level.getEntity(id) instanceof Villager villager && villager.isAlive() ? villager : null;
	}

	/** Name to show for the leader, loaded or not; null when the village has no leader. */
	@Nullable
	public static Component displayName(ServerLevel level, VillageRecord village) {
		Villager leader = getLeader(level, village);
		if (leader != null) {
			return leader.getName();
		}
		String base = village.getLeaderUuid() == null ? null : village.getTitledBaseNames().get(village.getLeaderUuid());
		return base == null ? null : LVText.tr(TITLE_KEY, base);
	}

	private static void replace(ServerLevel level, VillageRecord village, @Nullable Villager oldLeader, @Nullable Villager newLeader, long now) {
		if (oldLeader != null) {
			removeTitle(village, oldLeader);
		}
		village.setLeaderUuid(newLeader == null ? null : newLeader.getUUID());
		village.setLeaderLastSeenTick(now);
		if (newLeader != null) {
			applyTitle(village, newLeader);
		}
		LivingVillages.debug("Village {} leader is now {} ({})", village.getId(),
				newLeader == null ? "nobody" : newLeader.getName().getString(), newLeader == null ? "-" : newLeader.getUUID());
		VillageEvents.LEADER_CHANGED.post(new VillageEvents.LeaderChanged(level, village, oldLeader, newLeader));
	}

	/** Highest profession level, then the villager this mod has known longest; never a nitwit or a builder. */
	@Nullable
	private static Villager choose(VillageRecord village, List<Villager> adults, long now) {
		return adults.stream()
				.filter(v -> isCandidate(village, v))
				.min(Comparator.comparing((Villager v) -> !hasJob(v)) // jobless villagers cannot trade
						.thenComparingInt((Villager v) -> -v.getVillagerData().getLevel())
						.thenComparingLong(v -> village.getKnownVillagers().getOrDefault(v.getUUID(), now))
						.thenComparing(Villager::getUUID))
				.orElse(null);
	}

	/** Never a nitwit, and never the villager building the current project. */
	private static boolean isCandidate(VillageRecord village, Villager villager) {
		BuildProject project = village.getProject();
		UUID builder = project == null ? null : project.getBuilderUuid();
		return villager.getVillagerData().getProfession() != VillagerProfession.NITWIT && !villager.getUUID().equals(builder);
	}

	private static boolean hasJob(Villager villager) {
		return villager.getVillagerData().getProfession() != VillagerProfession.NONE;
	}

	/**
	 * Puts "Chief <name>" on the villager. The base name is its own name if it has one (name tag, another mod, a
	 * rename by a player), otherwise a name from the pool; it is kept so the title can be removed later.
	 */
	private static void applyTitle(VillageRecord village, Villager villager) {
		String base = village.getTitledBaseNames().get(villager.getUUID());
		Component current = villager.getCustomName();
		if (base != null && current != null && current.getString().equals(LVText.format(TITLE_KEY, base))) {
			return; // already titled
		}
		if (current != null && (base == null || !current.getString().contains(base))) {
			base = current.getString(); // named by a player or another mod: keep that name
		} else if (base == null) {
			base = NamePool.randomGivenName(villager.getRandom());
		}
		village.getTitledBaseNames().put(villager.getUUID(), base);
		villager.setCustomName(LVText.tr(TITLE_KEY, base));
		villager.setCustomNameVisible(false);
	}

	private static void removeTitle(VillageRecord village, Villager villager) {
		String base = village.getTitledBaseNames().remove(villager.getUUID());
		if (base != null) {
			villager.setCustomName(Component.literal(base));
		}
	}

	@Nullable
	private static Villager findIn(List<Villager> adults, UUID id) {
		for (Villager villager : adults) {
			if (villager.getUUID().equals(id)) {
				return villager;
			}
		}
		return null;
	}
}
