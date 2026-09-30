package com.khanhvu.livingvillages.chronicle;

import com.khanhvu.livingvillages.util.LVText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of a village chronicle (spec v2-GĐ 7.2): the Minecraft day, a lang key and its arguments. Arguments are
 * kept raw and turned into text when shown, so a language change applies to old entries too. An argument starting
 * with {@link #KEY_ARG} is itself a lang key, one starting with {@link #ENTITY_ARG} an entity type id.
 */
public record ChronicleEntry(long day, String key, List<String> args) {
	public static final String KEY_ARG = "@k:";
	public static final String ENTITY_ARG = "@e:";
	public static final String ITEM_ARG = "@i:";

	public static String keyArg(String key) {
		return KEY_ARG + key;
	}

	public static String entityArg(EntityType<?> type) {
		return ENTITY_ARG + BuiltInRegistries.ENTITY_TYPE.getKey(type);
	}

	public static String itemArg(Item item) {
		return ITEM_ARG + BuiltInRegistries.ITEM.getKey(item);
	}

	/** "Day 20: Tran Minh was born." Item names stay translatable, so every player reads them in their language. */
	public Component render() {
		return LVText.compose("livingvillages.chronicle.entry", day, message());
	}

	/** The entry without its day. */
	public Component message() {
		Object[] values = new Object[args.size()];
		for (int i = 0; i < values.length; i++) {
			String arg = args.get(i);
			if (arg.startsWith(ITEM_ARG)) {
				ResourceLocation id = ResourceLocation.tryParse(arg.substring(ITEM_ARG.length()));
				values[i] = id == null ? arg : Component.translatable(BuiltInRegistries.ITEM.get(id).getDescriptionId());
			} else {
				values[i] = resolve(arg);
			}
		}
		return LVText.compose(key, values);
	}

	/** The entry without its day, as plain text (logs). */
	public String text() {
		return message().getString();
	}

	private static String resolve(String arg) {
		if (arg.startsWith(KEY_ARG)) {
			return LVText.format(arg.substring(KEY_ARG.length()));
		}
		if (arg.startsWith(ENTITY_ARG)) {
			ResourceLocation id = ResourceLocation.tryParse(arg.substring(ENTITY_ARG.length()));
			if (id == null) {
				return arg;
			}
			// The mod's own name for common killers, else the vanilla name (English on a server).
			String own = "livingvillages.entity." + id.getPath();
			if (LVText.has(own)) {
				return LVText.format(own);
			}
			return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t -> t.getDescription().getString()).orElse(id.getPath());
		}
		return arg;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Day", day);
		tag.putString("Key", key);
		ListTag list = new ListTag();
		for (String arg : args) {
			list.add(StringTag.valueOf(arg));
		}
		tag.put("Args", list);
		return tag;
	}

	public static ChronicleEntry load(CompoundTag tag) {
		ListTag list = tag.getList("Args", Tag.TAG_STRING);
		List<String> args = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			args.add(list.getString(i));
		}
		return new ChronicleEntry(tag.getLong("Day"), tag.getString("Key"), List.copyOf(args));
	}
}
