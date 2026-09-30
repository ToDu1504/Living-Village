package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageLevel;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks a flat, dry, empty spot near the village bell for a house. Never loads chunks: a candidate touching an
 * unloaded chunk is rejected before any block or heightmap query.
 */
public final class SiteFinder {
	/** How far below the heightmap we look through snow layers, grass and flowers for the real ground. */
	private static final int MAX_COVER_DEPTH = 4;
	/** POIs this far below the floor still count as "inside" the site (e.g. a bed in a basement). */
	private static final int POI_DEPTH = 8;
	/** How far below the heightmap we look through a tree (trunk, canopy) for the ground it stands on. */
	private static final int MAX_TREE_DEPTH = 48;

	private SiteFinder() {
	}

	public static Optional<BuildSite> find(ServerLevel level, VillageRecord village, BuildingTemplate house, RandomSource random) {
		LVConfig config = LVConfig.get();
		VillageRegistry registry = VillageRegistry.get(level);
		BlockPos bell = village.getBellPos();
		int attempts = config.siteAttempts;
		// A site without trees wins at once; otherwise the one with the fewest trees to fell.
		BuildSite best = null;
		for (int i = 0; i < attempts; i++) {
			// Rings from the inner to the outer radius: closer to the bell first.
			int maxDistance = Math.max(config.minBuildDistance, VillageLevel.buildRadius(village));
			double radius = attempts == 1 ? config.minBuildDistance
					: config.minBuildDistance + (maxDistance - config.minBuildDistance) * (double) i / (attempts - 1);
			double angle = random.nextDouble() * Math.PI * 2.0;
			int x = bell.getX() + Mth.floor(Math.cos(angle) * radius);
			int z = bell.getZ() + Mth.floor(Math.sin(angle) * radius);
			for (Rotation rotation : Util.shuffledCopy(Rotation.values(), random)) {
				BuildSite site = tryCandidate(level, registry, house, x, z, rotation, config);
				if (site == null) {
					continue;
				}
				if (site.treeRoots().isEmpty()) {
					return Optional.of(site);
				}
				if (best == null || site.treeRoots().size() < best.treeRoots().size()) {
					best = site;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * A flat square of natural ground with {@code clearHeight} free blocks above it, between {@code minDistance} and
	 * {@code maxDistance} from the bell, away from plots, projects and POIs (spec v2-GĐ 7.4 graveyard). The box
	 * returned spans the free layers above the ground; ground heights inside differ by at most one block.
	 */
	public static Optional<BoundingBox> findFlatArea(ServerLevel level, VillageRecord village, int size, int clearHeight,
			int minDistance, int maxDistance, RandomSource random) {
		LVConfig config = LVConfig.get();
		VillageRegistry registry = VillageRegistry.get(level);
		BlockPos bell = village.getBellPos();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		candidates:
		for (int i = 0; i < config.siteAttempts; i++) {
			double radius = minDistance + (maxDistance - minDistance) * random.nextDouble();
			double angle = random.nextDouble() * Math.PI * 2.0;
			int minX = bell.getX() + Mth.floor(Math.cos(angle) * radius) - size / 2;
			int minZ = bell.getZ() + Mth.floor(Math.sin(angle) * radius) - size / 2;
			int maxX = minX + size - 1;
			int maxZ = minZ + size - 1;
			if (!allChunksLoaded(level, minX - 1, minZ - 1, maxX + 1, maxZ + 1)) {
				continue;
			}
			for (VillageRecord other : registry.getVillages()) {
				for (BoundingBox plot : other.getPlots()) {
					if (plot.intersects(minX - 1, minZ - 1, maxX + 1, maxZ + 1)) {
						continue candidates;
					}
				}
				BuildProject project = other.getProject();
				if (project != null && project.getFootprint().intersects(minX - 1, minZ - 1, maxX + 1, maxZ + 1)) {
					continue candidates;
				}
			}
			int low = Integer.MAX_VALUE;
			int high = Integer.MIN_VALUE;
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					int groundY = groundTop(level, x, z, pos, false);
					if (!isNaturalGround(level.getBlockState(pos.set(x, groundY, z)))) {
						continue candidates;
					}
					for (int y = groundY + 1; y <= groundY + clearHeight; y++) {
						if (!isClearable(level.getBlockState(pos.set(x, y, z)))) {
							continue candidates;
						}
					}
					low = Math.min(low, groundY);
					high = Math.max(high, groundY);
					if (high - low > 1) {
						continue candidates;
					}
				}
			}
			if (containsPoi(level, minX, minZ, maxX, maxZ, low - POI_DEPTH, high + clearHeight)) {
				continue;
			}
			return Optional.of(new BoundingBox(minX, low + 1, minZ, maxX, high + clearHeight, maxZ));
		}
		return Optional.empty();
	}

	/** Top ground block of a column (through snow layers and plants). The chunk must be loaded. */
	public static int groundTop(ServerLevel level, int x, int z) {
		return groundTop(level, x, z, new BlockPos.MutableBlockPos(), false);
	}

	/** Like {@link #groundTop(ServerLevel, int, int)}, also looking through the trunk and leaves of a tree. */
	public static int groundTopThroughTrees(ServerLevel level, int x, int z) {
		return groundTop(level, x, z, new BlockPos.MutableBlockPos(), true);
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

	private static BuildSite tryCandidate(ServerLevel level, VillageRegistry registry, BuildingTemplate house,
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
		boolean treesAllowed = config.allowTreeClearing;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				boolean inMargin = !content.isInside(x, content.minY(), z);
				int groundY = groundTop(level, x, z, pos, treesAllowed && !inMargin);
				BlockState ground = level.getBlockState(pos.set(x, groundY, z));
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
		// only air or replaceable plants above it. Buildings and water reject the site. Natural trees rooted in the
		// footprint are felled first and natural leaves are cleared, up to maxTreesPerSite trees.
		List<TreeFeller.Tree> trees = new ArrayList<>();
		Set<BlockPos> treeLogs = new HashSet<>();
		for (int x = content.minX(); x <= content.maxX(); x++) {
			for (int z = content.minZ(); z <= content.maxZ(); z++) {
				int s = surface[(x - minX) * sizeZ + (z - minZ)];
				for (int y = floorY; y <= footprint.maxY(); y++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					boolean ok = y < s ? isNaturalGround(state) || isClearable(state) : isClearable(state);
					if (!ok && treesAllowed) {
						ok = TreeFeller.isNaturalLeaves(state)
								|| (TreeFeller.isTreeLog(state) && acceptTree(level, pos.immutable(), content, trees, treeLogs, config));
					}
					if (!ok) {
						return null;
					}
				}
			}
		}

		if (containsPoi(level, minX, minZ, maxX, maxZ, floorY - POI_DEPTH, footprint.maxY())) {
			return null;
		}
		return site.withTrees(trees.stream().map(TreeFeller.Tree::root).toList());
	}

	/** Whether the log at {@code pos} belongs to a natural tree that may be felled for this site. */
	private static boolean acceptTree(ServerLevel level, BlockPos pos, BoundingBox content, List<TreeFeller.Tree> trees,
			Set<BlockPos> treeLogs, LVConfig config) {
		if (treeLogs.contains(pos)) {
			return true;
		}
		TreeFeller.Tree tree = TreeFeller.findTree(level, pos, config.maxTreeLogs);
		// Only trees growing inside the footprint; a trunk leaning in from outside stays an obstacle.
		if (tree == null || !content.isInside(tree.root().getX(), content.minY(), tree.root().getZ())) {
			return false;
		}
		trees.add(tree);
		treeLogs.addAll(tree.logs());
		return trees.size() <= config.maxTreesPerSite;
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

	/**
	 * Top ground block of a column, looking through snow layers and plants that the heightmap may count and, with
	 * {@code throughTrees}, through tree trunks and leaves (whether the tree may be felled is checked later).
	 */
	private static int groundTop(ServerLevel level, int x, int z, BlockPos.MutableBlockPos pos, boolean throughTrees) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int maxDepth = throughTrees ? MAX_TREE_DEPTH : MAX_COVER_DEPTH;
		for (int i = 0; i < maxDepth && y > level.getMinBuildHeight(); i++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			boolean cover = isClearable(state)
					|| (throughTrees && (TreeFeller.isTreeLog(state) || TreeFeller.isNaturalLeaves(state)));
			if (!cover) {
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
