package com.khanhvu.livingvillages.util;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.village.VillageRecord;

import java.util.Locale;

/**
 * Central logging (spec v4 mini-update M6): one line format for everything the mod logs, instead of the mixed
 * {@code LivingVillages.debug}/{@code LOGGER.info}/{@code LOGGER.warn} calls this replaces. A milestone always
 * prints; a detail only when {@code debugLogging} is on and its category is in {@code logCategories}.
 */
public final class LVLog {
	public enum Category {
		LEVEL, BUILD, PLAN, WALL, ROAD, GRADE, WORK, SOCIETY, CARE, BOARD, CHRONICLE, TEMPLATE;

		/** Config key, matching {@code LVConfig.logCategories} (lowercase enum name). */
		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private LVLog() {
	}

	/** Always logged: an important event for one village (level up, building finished, wall done...). */
	public static void milestone(Category category, VillageRecord village, String message, Object... args) {
		LivingVillages.LOGGER.info(line(category, village, message), args);
	}

	/** Always logged: an important event not tied to one village (startup, config problems). */
	public static void milestone(String message, Object... args) {
		LivingVillages.LOGGER.info("[LV] " + message, args);
	}

	/** Always logged, at warn level (bad config, missing template, read/write failures). */
	public static void warn(String message, Object... args) {
		LivingVillages.LOGGER.warn("[LV] " + message, args);
	}

	/** Always logged, at warn level, for a problem with one village's project (template gone, too many blocked steps). */
	public static void warn(Category category, VillageRecord village, String message, Object... args) {
		LivingVillages.LOGGER.warn(line(category, village, message), args);
	}

	/** Only when {@code debugLogging} is on and {@code category} is enabled: routine detail for one village. */
	public static void detail(Category category, VillageRecord village, String message, Object... args) {
		if (enabled(category)) {
			LivingVillages.LOGGER.info(line(category, village, message), args);
		}
	}

	/** Only when {@code debugLogging} is on and {@code category} is enabled: routine detail with no single village. */
	public static void detail(Category category, String message, Object... args) {
		if (enabled(category)) {
			LivingVillages.LOGGER.info("[LV] [" + category.key() + "] " + message, args);
		}
	}

	/**
	 * A periodic per-village summary line (spec v4 mini-update M6, config {@code logSummaryMinutes}): always
	 * printed when the caller decides it is time, regardless of {@code debugLogging} or {@code logCategories} -
	 * the whole point is to read village progress from the log without turning debug on.
	 */
	public static void summary(VillageRecord village, String message, Object... args) {
		LivingVillages.LOGGER.info("[LV] [sum] " + tag(village) + " - " + message, args);
	}

	public static boolean enabled(Category category) {
		LVConfig config = LVConfig.get();
		return config.debugLogging && config.logCategories.contains(category.key());
	}

	private static String line(Category category, VillageRecord village, String message) {
		return "[LV] [" + category.key() + "] " + tag(village) + " - " + message;
	}

	/** "Greenford (town)", or bell coordinates before the village has a name or a level. */
	public static String tag(VillageRecord village) {
		String name = VillageIdentity.displayName(village);
		if (VillageLevel.enabled() && village.getLevel() >= 0) {
			return name + " (" + VillageLevel.name(village.getLevel()) + ")";
		}
		return name;
	}
}
