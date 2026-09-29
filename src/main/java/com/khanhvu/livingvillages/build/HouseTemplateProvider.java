package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.mixin.SinglePoolElementAccessor;
import com.khanhvu.livingvillages.mixin.StructureTemplateAccessor;
import com.khanhvu.livingvillages.village.VillageType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads house templates from the village house pools at runtime, so datapacks and mods that replace
 * those pools or their .nbt files (e.g. Better Village) are picked up automatically.
 * A house is a template with at least one bed and no job-site block (Better Village adds beds to smithies,
 * libraries and so on). Results are cached per pool until datapack reload.
 */
public final class HouseTemplateProvider {
	/** Guards against odd templates from other mods (see spec section 11). */
	private static final int MAX_HORIZONTAL_SIZE = 24;
	private static final int MAX_HEIGHT = 20;

	private static final Map<ResourceLocation, List<HouseTemplate>> CACHE = new HashMap<>();
	private static final Set<ResourceLocation> WARNED_POOLS = new HashSet<>();

	private HouseTemplateProvider() {
	}

	public static void clearCache() {
		CACHE.clear();
		WARNED_POOLS.clear();
	}

	/**
	 * Houses for the given village type, sorted by id. Falls back to plains when the type's pool has no usable house.
	 */
	public static List<HouseTemplate> getHouses(ServerLevel level, VillageType type) {
		List<HouseTemplate> houses = getHousesFromPool(level, type.getHousePool());
		if (houses.isEmpty() && type != VillageType.PLAINS) {
			warnOnce(type.getHousePool(), "has no house with a bed, falling back to plains");
			houses = getHousesFromPool(level, VillageType.PLAINS.getHousePool());
		}
		return houses;
	}

	/** Weighted random pick, matching the pool's own weights. */
	@Nullable
	public static HouseTemplate pickRandom(List<HouseTemplate> houses, RandomSource random) {
		int total = 0;
		for (HouseTemplate house : houses) {
			total += house.weight();
		}
		if (total <= 0) {
			return null;
		}
		int roll = random.nextInt(total);
		for (HouseTemplate house : houses) {
			roll -= house.weight();
			if (roll < 0) {
				return house;
			}
		}
		return null;
	}

	@Nullable
	public static HouseTemplate findById(ServerLevel level, VillageType type, ResourceLocation id) {
		for (HouseTemplate house : getHouses(level, type)) {
			if (house.id().equals(id)) {
				return house;
			}
		}
		return null;
	}

	private static List<HouseTemplate> getHousesFromPool(ServerLevel level, ResourceLocation poolId) {
		List<HouseTemplate> cached = CACHE.get(poolId);
		if (cached == null) {
			cached = loadPool(level, poolId);
			CACHE.put(poolId, cached);
		}
		return cached;
	}

	private static List<HouseTemplate> loadPool(ServerLevel level, ResourceLocation poolId) {
		StructureTemplatePool pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).get(poolId);
		if (pool == null) {
			warnOnce(poolId, "does not exist");
			return List.of();
		}

		StructureTemplateManager manager = level.getStructureManager();
		HolderLookup<Block> blockLookup = level.holderLookup(Registries.BLOCK);
		// The pool's element list repeats each element by its weight; the fixed seed only affects order, which we sort away.
		Map<ResourceLocation, HouseTemplate> byId = new LinkedHashMap<>();
		Set<ResourceLocation> rejected = new HashSet<>();
		for (StructurePoolElement element : pool.getShuffledTemplates(RandomSource.create(0L))) {
			if (!(element instanceof SinglePoolElement single)) {
				continue; // empty, feature and list elements are not houses
			}
			Optional<ResourceLocation> templateId = ((SinglePoolElementAccessor) single).livingvillages$getTemplate().left();
			if (templateId.isEmpty()) {
				continue; // inline templates have no id to save in a build project
			}
			ResourceLocation id = templateId.get();
			if (rejected.contains(id)) {
				continue;
			}
			HouseTemplate existing = byId.get(id);
			if (existing != null) {
				byId.put(id, existing.withWeight(existing.weight() + 1));
				continue;
			}
			HouseTemplate house = loadTemplate(manager, blockLookup, id, single instanceof LegacySinglePoolElement);
			if (house != null) {
				byId.put(id, house);
			} else {
				rejected.add(id);
			}
		}

		List<HouseTemplate> houses = new ArrayList<>(byId.values());
		houses.sort(Comparator.comparing(h -> h.id().toString()));
		if (houses.isEmpty()) {
			warnOnce(poolId, "contains no usable house with a bed");
		}
		LivingVillages.debug("Pool {}: {} houses with beds", poolId, houses.size());
		return List.copyOf(houses);
	}

	@Nullable
	private static HouseTemplate loadTemplate(StructureTemplateManager manager, HolderLookup<Block> blockLookup, ResourceLocation id, boolean legacy) {
		Optional<StructureTemplate> optional = manager.get(id);
		if (optional.isEmpty()) {
			return null;
		}
		StructureTemplate template = optional.get();
		List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).livingvillages$getPalettes();
		if (palettes.isEmpty()) {
			return null;
		}

		List<StructureTemplate.StructureBlockInfo> processed = new ArrayList<>();
		int beds = 0;
		int streetJigsawY = Integer.MAX_VALUE;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
			if (isStreetJigsaw(info.state())) {
				streetJigsawY = Math.min(streetJigsawY, info.pos().getY());
			}
			StructureTemplate.StructureBlockInfo block = preprocess(info, blockLookup, id);
			if (block == null) {
				continue;
			}
			BlockState state = block.state();
			if (!state.isAir()) {
				if (isJobSite(state)) {
					LivingVillages.debug("Skipping template {}: contains job site {}", id, state.getBlock());
					return null; // a work building (smithy, library...) even if it has a bed
				}
				if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
					beds++;
				}
				BlockPos pos = block.pos();
				minX = Math.min(minX, pos.getX());
				minY = Math.min(minY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX());
				maxY = Math.max(maxY, pos.getY());
				maxZ = Math.max(maxZ, pos.getZ());
			}
			processed.add(block);
		}
		if (beds == 0) {
			return null; // farms, animal pens, decorations...
		}

		BoundingBox content = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
		if (content.getXSpan() > MAX_HORIZONTAL_SIZE || content.getZSpan() > MAX_HORIZONTAL_SIZE || content.getYSpan() > MAX_HEIGHT) {
			LivingVillages.debug("Skipping template {}: too large ({}x{}x{})", id, content.getXSpan(), content.getYSpan(), content.getZSpan());
			return null;
		}

		int floorY = streetJigsawY;
		if (floorY == Integer.MAX_VALUE) {
			LivingVillages.debug("Template {}: no street jigsaw, bottom layer used as floor", id);
			floorY = content.minY();
		}

		// Legacy elements (all vanilla village houses) never place air. Other elements do, but only inside the house bounds.
		List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>(processed.size());
		for (StructureTemplate.StructureBlockInfo block : processed) {
			if (!block.state().isAir() || (!legacy && content.isInside(block.pos()))) {
				blocks.add(block);
			}
		}
		return new HouseTemplate(id, template, List.copyOf(blocks), content, floorY, beds, 1);
	}

	/**
	 * The jigsaw that attaches a house to the street faces sideways; the upward ones only mark villager spawns.
	 * Vanilla places a house so that this jigsaw's layer is the first free block above the terrain.
	 */
	private static boolean isStreetJigsaw(BlockState state) {
		return state.is(Blocks.JIGSAW) && state.getValue(JigsawBlock.ORIENTATION).front().getAxis().isHorizontal();
	}

	private static boolean isJobSite(BlockState state) {
		return PoiTypes.forState(state).map(holder -> holder.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)).orElse(false);
	}

	/**
	 * Replaces jigsaw blocks by their final_state and drops blocks that must never be placed.
	 * Returns null for blocks to skip.
	 */
	@Nullable
	private static StructureTemplate.StructureBlockInfo preprocess(StructureTemplate.StructureBlockInfo info, HolderLookup<Block> blockLookup, ResourceLocation templateId) {
		BlockState state = info.state();
		if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK)) {
			return null;
		}
		if (!state.is(Blocks.JIGSAW)) {
			return info;
		}
		if (info.nbt() == null) {
			return null;
		}
		String finalState = info.nbt().getString("final_state");
		try {
			BlockState replacement = BlockStateParser.parseForBlock(blockLookup, finalState, true).blockState();
			if (replacement.is(Blocks.STRUCTURE_VOID)) {
				return null;
			}
			return new StructureTemplate.StructureBlockInfo(info.pos(), replacement, null);
		} catch (CommandSyntaxException e) {
			LivingVillages.debug("Template {}: bad jigsaw final_state '{}', skipped", templateId, finalState);
			return null;
		}
	}

	private static void warnOnce(ResourceLocation poolId, String message) {
		if (WARNED_POOLS.add(poolId)) {
			LivingVillages.LOGGER.warn("[LivingVillages] Pool {} {}", poolId, message);
		}
	}
}
