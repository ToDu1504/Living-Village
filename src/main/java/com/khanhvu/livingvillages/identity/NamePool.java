package com.khanhvu.livingvillages.identity;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Given names for villagers, from the bundled {@code data/livingvillages/names/villagers_<language>.json}
 * (a JSON array of strings). v2-GĐ 5 adds surnames and village names in the same folder.
 */
public final class NamePool {
	private static List<String> givenNames = List.of();

	private NamePool() {
	}

	public static void load() {
		givenNames = read("villagers_" + LVConfig.get().language);
		if (givenNames.isEmpty()) {
			givenNames = read("villagers_en_us");
		}
	}

	public static String randomGivenName(RandomSource random) {
		return givenNames.isEmpty() ? "Villager" : givenNames.get(random.nextInt(givenNames.size()));
	}

	private static List<String> read(String file) {
		String path = "/data/livingvillages/names/" + file + ".json";
		try (InputStream in = LivingVillages.class.getResourceAsStream(path)) {
			if (in == null) {
				return List.of();
			}
			List<String> names = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<List<String>>() { }.getType());
			return names == null ? List.of() : List.copyOf(names);
		} catch (IOException | JsonParseException e) {
			LivingVillages.LOGGER.warn("[LivingVillages] Could not read {}: {}", path, e.getMessage());
			return List.of();
		}
	}
}
