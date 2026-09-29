package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Picks a flat, dry, empty spot near the village bell for a house. Never loads chunks: a candidate touching an
 * unloaded chunk is rejected before any block or heightmap query.
 */
public final class SiteFinder {
	/** How far below the heightmap we look through snow layers, grass and flowers for the real ground. */
	private static final int MAX_COVER_DEPTH = 4;
	/** POIs this far below the floor still count as "inside" the site (e.g. a bed in a basement). */
	private static final int POI_DEPTH = 8;

	private SiteFinder() {
	}

	public static Optional<BuildSite> find(ServerLevel level, VillageRecord village, HouseTemplate house, RandomSource random) {
		LVConfig config = LVConfig.get();
		VillageRegistry registry = VillageRegistry.get(level);
		BlockPos bell = village.getBellPos();
		int attempts = config.siteAttempts;
		for (int i = 0; i < attempts; i++) {
			// Rings from the inner to the outer radius: closer to the bell first.
			double radius = attempts == 1 ? config.minBuildDistance
					: config.minBuildDistance + (config.maxBuildDistance - config.minBuildDistance) * (double) i / (attempts - 1);
			double angle = random.nextDouble() * Math.PI * 2.0;
			int x = bell.getX() + Mth.floor(Math.cos(angle) * radius);
			int z = bell.getZ() + Mth.floor(Math.sin(angle) * radius);
			for (Rotation rotation : Util.shuffledCopy(Rotation.values(), random)) {
				BuildSite site = tryCandidate(level, registry, house, x, z, rotation, config);
				if (site != null) {
					return Optional.of(site);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Ground a house may stand on or that may be levelled away inside its footprint. Everything else
	 * (paths, planks, cobblestone, water, ice, logs...) is treated as something not to build over.
	 */
	public static boolean isNaturalGround(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
				|| state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.CLAY)
				|| state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA);
	}

	/** Air, grass, flowers, snow layers: things a house may replace. Fluids are not included. */
	public static boolean isClearable(BlockState state) {
		return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
	}

	private static BuildSite tryCandidate(ServerLevel level, VillageRegistry registry, HouseTemplate house,
			int centerX, int centerZ, Rotation rotation, LVConfig config) {
		BoundingBox local = house.worldBox(BlockPos.ZERO, rotation);
		int originX = centerX - (local.minX() + local.maxX()) / 2;
		int originZ = centerZ - (local.minZ() + local.maxZ()) / 2;
		BoundingBox content = local.moved(originX, 0, originZ);
		int margin = config.margin;
		int minX = content.minX() - margin;
		int minZ = content.minZ() - margin;
		int maxX = content.maxX() + margin;
		int maxZ = content.maxZ() + margin;

		if (!allChunksLoaded(level, minX, minZ, maxX, maxZ)) {
			return null;
		}
		for (VillageRecord other : registry.getVillages()) {
			for (BoundingBox plot : other.getPlots()) {
				if (plot.intersects(minX, minZ, maxX, maxZ)) {
					return null;
				}
			}
			BuildProject project = other.getProject();
			if (project != null && project.getFootprint().intersects(minX, minZ, maxX, maxZ)) {
				return null;
			}
		}

		// Ground height and type over the footprint plus margin.
		int sizeX = maxX - minX + 1;
		int sizeZ = maxZ - minZ + 1;
		int[] surface = new int[sizeX * sizeZ];
		int minSurface = Integer.MAX_VALUE;
		int maxSurface = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int groundY = groundTop(level, x, z, pos);
				BlockState ground = level.getBlockState(pos.set(x, groundY, z));
				boolean inMargin = !content.isInside(x, content.minY(), z);
				if (inMargin && ground.is(BlockTags.LOGS)) {
					continue; // a tree next to the house is harmless; only trees inside the footprint block the site
				}
				// The house may stand next to a path, field or fence (vanilla houses do), not on one. Buildings next door
				// still reject the site through the slope check, since their walls stand far above the ground.
				if (!inMargin && !isNaturalGround(ground)) {
					return null;
				}
				int s = groundY + 1;
				surface[(x - minX) * sizeZ + (z - minZ)] = s;
				minSurface = Math.min(minSurface, s);
				maxSurface = Math.max(maxSurface, s);
				if (maxSurface - minSurface > config.maxHeightDifference) {
					return null;
				}
			}
		}

		int floor = modeSurface(surface, sizeZ, content, minX, minZ);
		BuildSite site = BuildSite.of(house, new BlockPos(originX, floor - house.floorY() + config.templateYOffset, originZ), rotation);
		BoundingBox footprint = site.footprint();
		int floorY = site.floorY(house);
		if (footprint.maxY() >= level.getMaxBuildHeight() || floorY - BuildOrder.MAX_FOUNDATION_DEPTH <= level.getMinBuildHeight()) {
			return null;
		}

		// The house volume must be empty: natural ground below each column's surface (levelled later),
		// only air or replaceable plants above it. Trees, buildings and water reject the site.
		for (int x = content.minX(); x <= content.maxX(); x++) {
			for (int z = content.minZ(); z <= content.maxZ(); z++) {
				int s = surface[(x - minX) * sizeZ + (z - minZ)];
				for (int y = floorY; y <= footprint.maxY(); y++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					boolean ok = y < s ? isNaturalGround(state) || isClearable(state) : isClearable(state);
					if (!ok) {
						return null;
					}
				}
			}
		}

		if (containsPoi(level, minX, minZ, maxX, maxZ, floorY - POI_DEPTH, footprint.maxY())) {
			return null;
		}
		return site;
	}

	private static boolean allChunksLoaded(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return false;
				}
			}
		}
		return true;
	}

	/** Top ground block of a column, looking through snow layers and plants that the heightmap may count. */
	private static int groundTop(ServerLevel level, int x, int z, BlockPos.MutableBlockPos pos) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		for (int i = 0; i < MAX_COVER_DEPTH && y > level.getMinBuildHeight(); i++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (!isClearable(state)) {
				break;
			}
			y--;
		}
		return y;
	}

	/** Most common surface height inside the house footprint (margin excluded); ties go to the higher level. */
	private static int modeSurface(int[] surface, int sizeZ, BoundingBox content, int minX, int minZ) {
		Map<Integer, Integer> counts = new HashMap<>();
		for (int x = content.minX(); x <= content.maxX(); x++) {
			for (int z = content.minZ(); z <= content.maxZ(); z++) {
				counts.merge(surface[(x - minX) * sizeZ + (z - minZ)], 1, Integer::sum);
			}
		}
		int best = Integer.MIN_VALUE;
		int bestCount = 0;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			int count = entry.getValue();
			if (count > bestCount || (count == bestCount && entry.getKey() > best)) {
				best = entry.getKey();
				bestCount = count;
			}
		}
		return best;
	}

	/** Any POI (bell, bed, job site...) in the box. Chunks are known to be loaded. */
	private static boolean containsPoi(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
		PoiManager poi = level.getPoiManager();
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				boolean found = poi.getInChunk(type -> true, new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
						.anyMatch(record -> {
							BlockPos p = record.getPos();
							return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ
									&& p.getY() >= minY && p.getY() <= maxY;
						});
				if (found) {
					return true;
				}
			}
		}
		return false;
	}
}
