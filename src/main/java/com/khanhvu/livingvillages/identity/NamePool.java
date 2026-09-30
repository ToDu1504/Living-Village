package com.khanhvu.livingvillages.identity;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageType;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Names from the bundled {@code data/livingvillages/names/} files for the configured language (spec v2-GĐ 5):
 * given names ({@code villagers_<lang>.json}), surnames ({@code surnames_<lang>.json}) and village names by style
 * ({@code villages_<lang>.json}: a {@code pattern} such as "Làng %s" and {@code names} per village type).
 */
public final class NamePool {
	private static List<String> givenNames = List.of();
	private static List<String> surnames = List.of();
	private static VillageNames villageNames = new VillageNames();

	private static final class VillageNames {
		String pattern = "%s";
		Map<String, List<String>> names = Map.of();
	}

	private NamePool() {
	}

	public static void load() {
		String language = LVConfig.get().language;
		givenNames = readOr("villagers_" + language, "villagers_en_us", new TypeToken<List<String>>() { }.getType(), List.of());
		surnames = readOr("surnames_" + language, "surnames_en_us", new TypeToken<List<String>>() { }.getType(), List.of());
		villageNames = readOr("villages_" + language, "villages_en_us", VillageNames.class, new VillageNames());
	}

	public static String randomGivenName(RandomSource random) {
		return pick(givenNames, "Villager", random);
	}

	public static String randomSurname(RandomSource random) {
		return pick(surnames, "Hill", random);
	}

	public static String randomVillageName(VillageType type, RandomSource random) {
		List<String> names = villageNames.names.getOrDefault(type.getSerializedName(), List.of());
		String base = pick(names, "Greenfield", random);
		try {
			return String.format(villageNames.pattern, base);
		} catch (RuntimeException e) {
			return base;
		}
	}

	private static String pick(List<String> list, String fallback, RandomSource random) {
		return list.isEmpty() ? fallback : list.get(random.nextInt(list.size()));
	}

	private static <T> T readOr(String file, String fallbackFile, Type type, T empty) {
		T value = read(file, type);
		if (value == null) {
			value = read(fallbackFile, type);
		}
		return value == null ? empty : value;
	}

	private static <T> T read(String file, Type type) {
		String path = "/data/livingvillages/names/" + file + ".json";
		try (InputStream in = LivingVillages.class.getResourceAsStream(path)) {
			if (in == null) {
				return null;
			}
			return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
		} catch (IOException | JsonParseException e) {
			LivingVillages.LOGGER.warn("[LivingVillages] Could not read {}: {}", path, e.getMessage());
			return null;
		}
	}
}
