package com.khanhvu.livingvillages.util;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.khanhvu.livingvillages.LivingVillages;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Translatable messages that still read correctly on clients without the mod.
 * The mod is server-side only, so a vanilla client has no livingvillages lang file; the bundled
 * en_us.json is used as the fallback text, keeping it the single source of English strings.
 */
public final class Lang {
	private static final String EN_US = "/assets/livingvillages/lang/en_us.json";
	private static Map<String, String> fallback = Map.of();

	private Lang() {
	}

	public static void load() {
		try (InputStream in = LivingVillages.class.getResourceAsStream(EN_US)) {
			if (in == null) {
				LivingVillages.LOGGER.warn("[LivingVillages] Missing {}", EN_US);
				return;
			}
			Map<String, String> map = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<Map<String, String>>() { }.getType());
			fallback = map == null ? Map.of() : Map.copyOf(map);
		} catch (IOException | JsonParseException e) {
			LivingVillages.LOGGER.warn("[LivingVillages] Could not read {}: {}", EN_US, e.getMessage());
		}
	}

	public static MutableComponent tr(String key, Object... args) {
		return Component.translatableWithFallback(key, fallback.get(key), args);
	}
}
