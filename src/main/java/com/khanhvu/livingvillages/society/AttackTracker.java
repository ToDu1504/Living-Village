package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;

/**
 * Counts monster attacks on villagers (spec v2-GĐ 2.4). Only observes: it listens after the damage is dealt and
 * never cancels anything. The count fades by half every {@code attackHalfLifeTicks}.
 */
public final class AttackTracker {
	private AttackTracker() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (blocked || damageTaken <= 0 || !(entity instanceof Villager) || !(source.getEntity() instanceof Enemy)
					|| !(entity.level() instanceof ServerLevel level) || !LVConfig.get().needsEnabled) {
				return;
			}
			VillageRecord village = VillageRegistry.get(level).findContaining(entity.blockPosition());
			if (village != null) {
				long now = level.getGameTime();
				village.setAttackScore(currentScore(village, now) + 1.0, now);
				VillageRegistry.get(level).setDirty();
			}
		});
	}

	/** Recent attacks, decayed to {@code now}. */
	public static double currentScore(VillageRecord village, long now) {
		double score = village.getAttackScore();
		if (score <= 0) {
			return 0;
		}
		long elapsed = Math.max(0, now - village.getAttackScoreTick());
		return score * Math.pow(0.5, (double) elapsed / LVConfig.get().attackHalfLifeTicks);
	}
}
