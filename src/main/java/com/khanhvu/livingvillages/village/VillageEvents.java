package com.khanhvu.livingvillages.village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Internal event bus (spec v2 §0.2). Systems publish what happened to a village; others (mood now; chronicle, voice
 * later) listen instead of calling each other directly. Listeners run on the server thread, synchronously.
 */
public final class VillageEvents {
	public record BuildingCompleted(ServerLevel level, VillageRecord village, String templateId) {
	}

	/** A villager of the village died (killed, not converted). */
	public record Death(ServerLevel level, VillageRecord village, Villager villager) {
	}

	public record LeaderChanged(ServerLevel level, VillageRecord village, @Nullable Villager oldLeader, @Nullable Villager newLeader) {
	}

	public static final Bus<BuildingCompleted> BUILDING_COMPLETED = new Bus<>();
	public static final Bus<Death> DEATH = new Bus<>();
	public static final Bus<LeaderChanged> LEADER_CHANGED = new Bus<>();

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
