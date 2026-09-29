package com.khanhvu.livingvillages.util;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every text players see. The mod is server-side only, so vanilla clients have no livingvillages lang file:
 * texts are resolved on the server from the bundled lang file of the configured {@code language}
 * (falling back to en_us, then to the key itself) and sent as literal components.
 */
public final class LVText {
	private static final String FALLBACK_LANGUAGE = "en_us";

	private static Map<String, String> selected = Map.of();
	private static Map<String, String> fallback = Map.of();
	private static final Set<String> WARNED_KEYS = new HashSet<>();

	private LVText() {
	}

	/** (Re)loads the lang files for the configured language. Call after the config is loaded. */
	public static void load() {
		fallback = read(FALLBACK_LANGUAGE);
		String language = LVConfig.get().language;
		selected = language.equals(FALLBACK_LANGUAGE) ? fallback : read(language);
		WARNED_KEYS.clear();
	}

	/** The text for {@code key} with {@code %s} placeholders filled; components are flattened to their text. */
	public static MutableComponent tr(String key, Object... args) {
		return Component.literal(format(key, args));
	}

	public static String format(String key, Object... args) {
		String pattern = selected.get(key);
		if (pattern == null) {
			pattern = fallback.get(key);
		}
		if (pattern == null) {
			if (WARNED_KEYS.add(key)) {
				LivingVillages.LOGGER.warn("[LivingVillages] Missing text for key {}", key);
			}
			return key;
		}
		Object[] plain = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			plain[i] = args[i] instanceof Component component ? component.getString() : args[i];
		}
		try {
			return String.format(Locale.ROOT, pattern, plain);
		} catch (IllegalFormatException e) {
			if (WARNED_KEYS.add(key)) {
				LivingVillages.LOGGER.warn("[LivingVillages] Bad placeholders in text {}: {}", key, e.getMessage());
			}
			return pattern;
		}
	}

	private static Map<String, String> read(String language) {
		String path = "/assets/livingvillages/lang/" + language + ".json";
		try (InputStream in = LivingVillages.class.getResourceAsStream(path)) {
			if (in == null) {
				LivingVillages.LOGGER.warn("[LivingVillages] Missing {}", path);
				return Map.of();
			}
			Map<String, String> map = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<Map<String, String>>() { }.getType());
			return map == null ? Map.of() : Map.copyOf(map);
		} catch (IOException | JsonParseException e) {
			LivingVillages.LOGGER.warn("[LivingVillages] Could not read {}: {}", path, e.getMessage());
			return Map.of();
		}
	}
}
