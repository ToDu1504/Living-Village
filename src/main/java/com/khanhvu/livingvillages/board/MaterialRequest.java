package com.khanhvu.livingvillages.board;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/** One material the village asks for on its board (spec v2-GĐ 9.1): {@code count} of {@code item} for {@code reward} emeralds. */
public final class MaterialRequest {
	private final ResourceLocation item;
	private final int count;
	private final int reward;
	private boolean fulfilled;

	public MaterialRequest(ResourceLocation item, int count, int reward) {
		this.item = item;
		this.count = count;
		this.reward = reward;
	}

	public ResourceLocation itemId() {
		return item;
	}

	public Item item() {
		return BuiltInRegistries.ITEM.get(item);
	}

	public int count() {
		return count;
	}

	public int reward() {
		return reward;
	}

	public boolean isFulfilled() {
		return fulfilled;
	}

	public void setFulfilled(boolean fulfilled) {
		this.fulfilled = fulfilled;
	}

	public boolean sameAs(MaterialRequest other) {
		return item.equals(other.item) && count == other.count && reward == other.reward;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Item", item.toString());
		tag.putInt("Count", count);
		tag.putInt("Reward", reward);
		tag.putBoolean("Fulfilled", fulfilled);
		return tag;
	}

	@Nullable
	public static MaterialRequest load(CompoundTag tag) {
		ResourceLocation item = ResourceLocation.tryParse(tag.getString("Item"));
		if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) {
			return null;
		}
		MaterialRequest request = new MaterialRequest(item, tag.getInt("Count"), tag.getInt("Reward"));
		request.fulfilled = tag.getBoolean("Fulfilled");
		return request;
	}
}
