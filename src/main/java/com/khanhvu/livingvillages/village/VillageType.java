package com.khanhvu.livingvillages.village;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Architectural style of a village. Decides which vanilla house pool is used and the foundation block.
 */
public enum VillageType {
	PLAINS("plains", Blocks.COBBLESTONE),
	DESERT("desert", Blocks.SANDSTONE),
	SAVANNA("savanna", Blocks.COBBLESTONE),
	SNOWY("snowy", Blocks.COBBLESTONE),
	TAIGA("taiga", Blocks.COBBLESTONE);

	private final String serializedName;
	private final ResourceLocation housePool;
	private final Block foundationBlock;

	VillageType(String serializedName, Block foundationBlock) {
		this.serializedName = serializedName;
		this.housePool = ResourceLocation.withDefaultNamespace("village/" + serializedName + "/houses");
		this.foundationBlock = foundationBlock;
	}

	public String getSerializedName() {
		return serializedName;
	}

	public ResourceLocation getHousePool() {
		return housePool;
	}

	public Block getFoundationBlock() {
		return foundationBlock;
	}

	/** Jungle, swamp and modded villager types have no vanilla village, so they fall back to plains. */
	public static VillageType fromVillagerType(VillagerType type) {
		if (type == VillagerType.DESERT) return DESERT;
		if (type == VillagerType.SAVANNA) return SAVANNA;
		if (type == VillagerType.SNOW) return SNOWY;
		if (type == VillagerType.TAIGA) return TAIGA;
		return PLAINS;
	}

	public static VillageType byName(String name) {
		for (VillageType type : values()) {
			if (type.serializedName.equals(name)) {
				return type;
			}
		}
		return null;
	}
}
