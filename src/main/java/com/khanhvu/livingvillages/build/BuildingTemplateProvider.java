package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.mixin.SinglePoolElementAccessor;
import com.khanhvu.livingvillages.mixin.StructureTemplateAccessor;
import com.khanhvu.livingvillages.village.VillageType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
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
 * Reads building templates from the village "houses" pools at runtime, so datapacks and mods that replace
 * those pools or their .nbt files (e.g. Better Village) are picked up automatically, and classifies them into
 * houses, farms, pens and workshops (spec v2-GĐ 3.1). Results are cached per pool until datapack reload.
 */
public final class BuildingTemplateProvider {
	/** Guards against odd templates from other mods (see spec section 11). */
	private static final int MAX_HORIZONTAL_SIZE = 24;
	private static final int MAX_HEIGHT = 20;
	/** Farmland blocks that make a template a farm. */
	private static final int MIN_FARMLAND = 6;
	/** Fence blocks (plus a gate) that make a template an animal pen. */
	private static final int MIN_PEN_FENCES = 12;

	private static final Map<ResourceLocation, List<BuildingTemplate>> CACHE = new HashMap<>();
	private static final Set<ResourceLocation> WARNED_POOLS = new HashSet<>();

	private BuildingTemplateProvider() {
	}

	public static void clearCache() {
		CACHE.clear();
		WARNED_POOLS.clear();
	}

	/**
	 * All usable buildings for the village type, sorted by id. Falls back to plains when the type's pool has no house.
	 */
	public static List<BuildingTemplate> getBuildings(ServerLevel level, VillageType type) {
		List<BuildingTemplate> buildings = getHousesFromPool(level, type.getHousePool());
		if (ofKind(buildings, BuildingKind.HOUSE).isEmpty() && type != VillageType.PLAINS) {
			warnOnce(type.getHousePool(), "has no house with a bed, falling back to plains");
			buildings = getHousesFromPool(level, VillageType.PLAINS.getHousePool());
		}
		return buildings;
	}

	/** Houses (templates with beds) for the village type. */
	public static List<BuildingTemplate> getHouses(ServerLevel level, VillageType type) {
		return ofKind(getBuildings(level, type), BuildingKind.HOUSE);
	}

	public static List<BuildingTemplate> ofKind(List<BuildingTemplate> buildings, BuildingKind kind) {
		return buildings.stream().filter(b -> b.kind() == kind).toList();
	}

	/** Weighted random pick, matching the pool's own weights. */
	@Nullable
	public static BuildingTemplate pickRandom(List<BuildingTemplate> buildings, RandomSource random) {
		int total = 0;
		for (BuildingTemplate building : buildings) {
			total += building.weight();
		}
		if (total <= 0) {
			return null;
		}
		int roll = random.nextInt(total);
		for (BuildingTemplate building : buildings) {
			roll -= building.weight();
			if (roll < 0) {
				return building;
			}
		}
		return null;
	}

	@Nullable
	public static BuildingTemplate findById(ServerLevel level, VillageType type, ResourceLocation id) {
		for (BuildingTemplate building : getBuildings(level, type)) {
			if (building.id().equals(id)) {
				return building;
			}
		}
		return null;
	}

	private static List<BuildingTemplate> getHousesFromPool(ServerLevel level, ResourceLocation poolId) {
		List<BuildingTemplate> cached = CACHE.get(poolId);
		if (cached == null) {
			cached = loadPool(level, poolId);
			CACHE.put(poolId, cached);
		}
		return cached;
	}

	private static List<BuildingTemplate> loadPool(ServerLevel level, ResourceLocation poolId) {
		StructureTemplatePool pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).get(poolId);
		if (pool == null) {
			warnOnce(poolId, "does not exist");
			return List.of();
		}

		StructureTemplateManager manager = level.getStructureManager();
		HolderLookup<Block> blockLookup = level.holderLookup(Registries.BLOCK);
		// The pool's element list repeats each element by its weight; the fixed seed only affects order, which we sort away.
		Map<ResourceLocation, BuildingTemplate> byId = new LinkedHashMap<>();
		Set<ResourceLocation> rejected = new HashSet<>();
		for (StructurePoolElement element : pool.getShuffledTemplates(RandomSource.create(0L))) {
			if (!(element instanceof SinglePoolElement single)) {
				continue; // empty, feature and list elements are not buildings
			}
			Optional<ResourceLocation> templateId = ((SinglePoolElementAccessor) single).livingvillages$getTemplate().left();
			if (templateId.isEmpty()) {
				continue; // inline templates have no id to save in a build project
			}
			ResourceLocation id = templateId.get();
			if (rejected.contains(id)) {
				continue;
			}
			BuildingTemplate existing = byId.get(id);
			if (existing != null) {
				byId.put(id, existing.withWeight(existing.weight() + 1));
				continue;
			}
			BuildingTemplate house = loadTemplate(manager, blockLookup, id, single instanceof LegacySinglePoolElement);
			if (house != null) {
				byId.put(id, house);
			} else {
				rejected.add(id);
			}
		}

		List<BuildingTemplate> buildings = new ArrayList<>(byId.values());
		buildings.sort(Comparator.comparing(b -> b.id().toString()));
		if (ofKind(buildings, BuildingKind.HOUSE).isEmpty()) {
			warnOnce(poolId, "contains no usable house with a bed");
		}
		LivingVillages.debug("Pool {}: {} buildings", poolId, buildings.size());
		return List.copyOf(buildings);
	}

	@Nullable
	private static BuildingTemplate loadTemplate(StructureTemplateManager manager, HolderLookup<Block> blockLookup, ResourceLocation id, boolean legacy) {
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
		int farmland = 0;
		int fences = 0;
		int gates = 0;
		Holder<PoiType> jobSite = null;
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
				Holder<PoiType> poi = jobSitePoi(state);
				if (poi != null && jobSite == null) {
					jobSite = poi;
				}
				if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
					beds++;
				} else if (state.is(Blocks.FARMLAND)) {
					farmland++;
				} else if (state.is(BlockTags.FENCES)) {
					fences++;
				} else if (state.is(BlockTags.FENCE_GATES)) {
					gates++;
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
		// Classify in this order (spec v2-GĐ 3.1): Better Village puts beds in most work buildings, so beds come last.
		// A farm must have its composter: farmland alone (vanilla plains_small_house_8's garden, some Better Village
		// pens) adds no farmer, and the village would keep building farms for nothing.
		BuildingKind kind;
		ResourceLocation profession = jobSite == null ? null : professionFor(jobSite);
		if (jobSite != null) {
			kind = VillagerProfession.FARMER.equals(BuiltInRegistries.VILLAGER_PROFESSION.get(profession)) && farmland >= MIN_FARMLAND
					? BuildingKind.FARM : BuildingKind.WORKSHOP;
		} else if (fences >= MIN_PEN_FENCES && gates > 0 && beds == 0) {
			// Only without beds: Better Village fences the gardens of its houses, and those stay houses.
			kind = BuildingKind.PEN;
		} else if (beds > 0) {
			kind = BuildingKind.HOUSE;
		} else {
			return null; // decorations, streets, meeting points...
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
		return new BuildingTemplate(id, template, List.copyOf(blocks), content, floorY, beds, 1, kind, profession);
	}

	/**
	 * The jigsaw that attaches a house to the street faces sideways; the upward ones only mark villager spawns.
	 * Vanilla places a house so that this jigsaw's layer is the first free block above the terrain.
	 */
	private static boolean isStreetJigsaw(BlockState state) {
		return state.is(Blocks.JIGSAW) && state.getValue(JigsawBlock.ORIENTATION).front().getAxis().isHorizontal();
	}

	@Nullable
	private static Holder<PoiType> jobSitePoi(BlockState state) {
		return PoiTypes.forState(state).filter(holder -> holder.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)).orElse(null);
	}

	/** The profession working at this job site, e.g. composter → farmer. */
	@Nullable
	private static ResourceLocation professionFor(Holder<PoiType> jobSite) {
		for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
			if (profession != VillagerProfession.NONE && profession.heldJobSite().test(jobSite)) {
				return BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
			}
		}
		return null;
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
