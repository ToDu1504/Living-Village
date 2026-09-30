package com.khanhvu.livingvillages.village;

import com.khanhvu.livingvillages.build.BuildingKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Internal event bus (spec v2 §0.2). Systems publish what happened to a village; others (mood now; chronicle, voice
 * later) listen instead of calling each other directly. Listeners run on the server thread, synchronously.
 */
public final class VillageEvents {
	public record BuildingCompleted(ServerLevel level, VillageRecord village, ResourceLocation templateId, @Nullable BuildingKind kind,
			BoundingBox footprint) {
	}

	/** A villager of the village died (killed, not converted). */
	public record Death(ServerLevel level, VillageRecord village, Villager villager, DamageSource source) {
	}

	public record LeaderChanged(ServerLevel level, VillageRecord village, @Nullable Villager oldLeader, @Nullable Villager newLeader) {
	}

	public record LevelUp(ServerLevel level, VillageRecord village, int newLevel) {
	}

	/** A festival starts; {@code nameKey} is its lang key, e.g. the harvest festival. */
	public record FestivalStarted(ServerLevel level, VillageRecord village, String nameKey) {
	}

	/** A player traded a board request with the leader (v2-GĐ 9.4); {@code player} is null if not known. */
	public record RequestFulfilled(ServerLevel level, VillageRecord village, @Nullable String player, ResourceLocation item, int count,
			boolean allDone) {
	}

	/** A baby villager was born in the village. */
	public record Birth(ServerLevel level, VillageRecord village, Villager baby) {
	}

	/** A zombie villager that a cleric of the village started curing became a villager again (v2-GĐ 4.5). */
	public record ZombieCured(ServerLevel level, VillageRecord village, @Nullable Villager cleric, Villager villager) {
	}

	public static final Bus<BuildingCompleted> BUILDING_COMPLETED = new Bus<>();
	public static final Bus<LevelUp> LEVEL_UP = new Bus<>();
	public static final Bus<Death> DEATH = new Bus<>();
	public static final Bus<LeaderChanged> LEADER_CHANGED = new Bus<>();
	public static final Bus<Birth> BIRTH = new Bus<>();
	public static final Bus<RequestFulfilled> REQUEST_FULFILLED = new Bus<>();
	public static final Bus<FestivalStarted> FESTIVAL = new Bus<>();
	public static final Bus<ZombieCured> ZOMBIE_CURED = new Bus<>();

	private VillageEvents() {
	}

	public static final class Bus<T> {
		private final List<Consumer<T>> listeners = new ArrayList<>();

		public void register(Consumer<T> listener) {
			listeners.add(listener);
		}

		public void post(T event) {
			for (Consumer<T> listener : listeners) {
				listener.accept(event);
			}
		}
	}
}
