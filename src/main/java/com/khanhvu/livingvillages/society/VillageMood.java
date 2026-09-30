package com.khanhvu.livingvillages.society;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.util.Mth;

import java.util.Iterator;
import java.util.Locale;

/**
 * Village mood 0–100 (spec v2-GĐ 2.2): the average of the four needs plus temporary effects that fade linearly to
 * zero over {@code moodEffectTicks} (new building +10, death −10…).
 */
public final class VillageMood {
	public static final int BUILDING_COMPLETED = 10;
	public static final int VILLAGER_DIED = -10;
	public static final int LEVEL_UP = 15;
	public static final int ZOMBIE_CURED = 5;

	public enum Level {
		MISERABLE, NORMAL, HAPPY;

		public static Level of(int mood) {
			return mood < 30 ? MISERABLE : mood > 70 ? HAPPY : NORMAL;
		}

		public String langKey() {
			return "livingvillages.mood." + name().toLowerCase(Locale.ROOT);
		}
	}

	private VillageMood() {
	}

	public static void addEffect(VillageRecord village, int amount, long now) {
		village.getMoodEffects().add(new VillageRecord.MoodEffect(amount, now));
	}

	/** Mood from {@code needs} and the effects still active at {@code now}; expired effects are dropped. */
	public static int compute(VillageRecord village, VillageNeeds needs, long now) {
		int duration = LVConfig.get().moodEffectTicks;
		double effects = 0;
		for (Iterator<VillageRecord.MoodEffect> it = village.getMoodEffects().iterator(); it.hasNext(); ) {
			VillageRecord.MoodEffect effect = it.next();
			double remaining = 1.0 - (double) (now - effect.startTick()) / duration;
			if (remaining <= 0) {
				it.remove();
			} else {
				effects += effect.amount() * Math.min(1.0, remaining);
			}
		}
		return Mth.clamp((int) Math.round(needs.average() + effects), 0, 100);
	}
}
