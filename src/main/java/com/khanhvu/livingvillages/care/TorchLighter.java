package com.khanhvu.livingvillages.care;

import com.khanhvu.livingvillages.util.LVLog;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.road.RoadSegment;
import com.khanhvu.livingvillages.road.VillageRoads;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.wall.VillageWalls;
import com.khanhvu.livingvillages.wall.WallBuilder;
import com.khanhvu.livingvillages.wall.WallRing;
import com.khanhvu.livingvillages.wall.WallShapes;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.List;

/**
 * Lights the village: a loose torch in dark spots while it is still growing (spec v3-GĐ 5), lamp posts lining the
 * streets once its roads are fully planned (spec v4 mini-update M7). Every torchIntervalTicks, one of the two:
 * <ul>
 * <li>Not fully planned yet (no frame, below lampMinLevel, or the four axes are not all paved): a random outdoor
 * spot is tried, inside the city walls first. Only spots no building will ever need get a torch: right beside a
 * road, on the square, or along the inside of the wall; at least torchSpacing from the mod's other torches.
 * <li>Fully planned (roadLamps on): a lamp post (the village's palisade material, lampHeight tall, a lantern on
 * top) goes up next to a road, spaced lampSpacing apart and staggered side to side, at the far end of each
 * finished axis, and at the four corners of the square; a loose torch within lampSpacing of a new post is cleared.
 * </ul>
 * Every block placed (torch, post or lantern) is remembered with the wall blocks, so one a player breaks is never
 * placed again, and one lost otherwise (explosion, fire) is rebuilt.
 */
public final class TorchLighter {
	/** Wall-block entries with this ring id are torches. */
	public static final int TORCH_RING = 0;
	/** Wall-block entries with this ring id are lamp posts; column 0 is the lantern, 1..lampHeight the fence below it. */
	public static final int LAMP_RING = VillageWalls.MAX_RING_ID - 1;
	private static final int SAMPLES = 16;
	private static final int INSIDE_TRIES = 8;
	private static final double SWING_RANGE = 8.0;

	private TorchLighter() {
	}

	public static boolean enabled() {
		return LVConfig.get().torchesEnabled && !WallBuilder.deferred();
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		if (!enabled() || Math.floorMod(level.getGameTime() + village.getId().hashCode(), config.torchIntervalTicks) != 0) {
			return;
		}
		if (config.roadLamps && fullyPlanned(village)) {
			tickLamps(level, village);
			return;
		}
		RandomSource random = level.getRandom();
		WallRing ring = village.getWalls().outer();
		for (int i = 0; i < SAMPLES; i++) {
			BlockPos spot = ring != null && random.nextInt(10) < 7 ? insideWalls(ring, random) : nearBell(village, random);
			if (spot != null && tryLight(level, village, spot.getX(), spot.getZ())) {
				VillageRegistry.get(level).setDirty();
				return; // one torch at a time
			}
		}
	}

	/** Number of torches the mod put up for the village. */
	public static int count(VillageRecord village) {
		int count = 0;
		for (int value : village.getWalls().getBlocks().values()) {
			if (VillageWalls.ringOf(value) == TORCH_RING) {
				count++;
			}
		}
		return count;
	}

	/** Number of lamp posts the mod put up for the village. */
	public static int lampPostCount(VillageRecord village) {
		int count = 0;
		for (int value : village.getWalls().getBlocks().values()) {
			if (VillageWalls.ringOf(value) == LAMP_RING && VillageWalls.columnOf(value) == 0) {
				count++;
			}
		}
		return count;
	}

	/** A frame, past lampMinLevel, with all four road axes paved (spec v4 mini-update M7). */
	private static boolean fullyPlanned(VillageRecord village) {
		if (village.getFrame() == null || village.getLevel() < LVConfig.get().lampMinLevel) {
			return false;
		}
		VillageRoads roads = village.getRoads();
		List<RoadSegment> segments = roads.getSegments();
		int axes = 0;
		for (int i = 0; i < segments.size(); i++) {
			if (segments.get(i).axis()) {
				if (!roads.done(i)) {
					return false;
				}
				axes++;
			}
		}
		return axes >= 4;
	}

	/** One lamp post per call: along a paved road, at the far end of a finished axis, or at a square corner. */
	private static void tickLamps(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		VillageRoads roads = village.getRoads();
		List<RoadSegment> segments = roads.getSegments();
		for (int s = 0; s < segments.size(); s++) {
			RoadSegment segment = segments.get(s);
			int paved = roads.progress(s);
			for (int i = 0; i < paved; i += config.lampSpacing) {
				if (tryLampBeside(level, village, segment, i, (i / config.lampSpacing) % 2 == 0)) {
					VillageRegistry.get(level).setDirty();
					return;
				}
			}
			if (segment.axis() && roads.done(s) && segment.length() > 1
					&& (tryLampBeside(level, village, segment, segment.length() - 1, true)
							|| tryLampBeside(level, village, segment, segment.length() - 1, false))) {
				VillageRegistry.get(level).setDirty();
				return;
			}
		}
		BlockPos bell = village.getBellPos();
		int plaza = config.plazaRadius;
		for (int corner = 0; corner < 4; corner++) {
			int x = bell.getX() + (((corner & 1) == 0) ? -plaza : plaza);
			int z = bell.getZ() + (((corner & 2) == 0) ? -plaza : plaza);
			if (tryLampAt(level, village, x, z)) {
				VillageRegistry.get(level).setDirty();
				return;
			}
		}
	}

	/** The shoulder of {@code segment} at column {@code i}, one side or the other across the paved width. */
	private static boolean tryLampBeside(ServerLevel level, VillageRecord village, RoadSegment segment, int i, boolean side) {
		BlockPos cell = segment.cell(i);
		int offset = LVConfig.get().roadWidth / 2 + 1;
		int x = segment.horizontal() ? cell.getX() : cell.getX() + (side ? offset : -offset);
		int z = segment.horizontal() ? cell.getZ() + (side ? offset : -offset) : cell.getZ();
		return tryLampAt(level, village, x, z);
	}

	private static BlockPos insideWalls(WallRing ring, RandomSource random) {
		int[] polygon = ring.getPolygon();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < polygon.length; i += 2) {
			minX = Math.min(minX, polygon[i]);
			maxX = Math.max(maxX, polygon[i]);
			minZ = Math.min(minZ, polygon[i + 1]);
			maxZ = Math.max(maxZ, polygon[i + 1]);
		}
		for (int t = 0; t < INSIDE_TRIES; t++) {
			int x = minX + random.nextInt(maxX - minX + 1);
			int z = minZ + random.nextInt(maxZ - minZ + 1);
			if (ring.contains(x + 0.5, z + 0.5)) {
				return new BlockPos(x, 0, z);
			}
		}
		return null;
	}

	private static BlockPos nearBell(VillageRecord village, RandomSource random) {
		int radius = VillageAnalyzer.areaRadius(village);
		BlockPos bell = village.getBellPos();
		return new BlockPos(bell.getX() + random.nextInt(radius * 2 + 1) - radius, 0, bell.getZ() + random.nextInt(radius * 2 + 1) - radius);
	}

	private static boolean tryLight(ServerLevel level, VillageRecord village, int x, int z) {
		LVConfig config = LVConfig.get();
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return false;
		}
		VillageWalls walls = village.getWalls();
		BlockPos ground = new BlockPos(x, SiteFinder.groundTop(level, x, z), z);
		BlockPos cell = ground.above();
		BlockState groundState = level.getBlockState(ground);
		boolean ownWall = walls.getBlocks().containsKey(ground.asLong()) && VillageWalls.ringOf(walls.getBlocks().get(ground.asLong())) != TORCH_RING;
		if (!(SiteFinder.isNaturalGround(groundState) || ownWall) || !groundState.getFluidState().isEmpty()
				|| RoadBuilder.roadBlocks().contains(groundState.getBlock()) || inField(village, x, z)
				|| !(ownWall || besideRoad(level, x, z) || onSquare(village, x, z, config) || alongWall(village, x, z, config))) {
			return false;
		}
		BlockState cellState = level.getBlockState(cell);
		BlockState torch = Blocks.TORCH.defaultBlockState();
		if (!SiteFinder.isClearable(cellState) || !level.canSeeSky(cell) || walls.getAbandoned().contains(cell.asLong())
				|| !groundState.isFaceSturdy(level, ground, Direction.UP) || !torch.canSurvive(level, cell)
				|| level.getBrightness(LightLayer.BLOCK, cell) > config.torchLightLevel || nearTorch(walls, cell, config.torchSpacing)) {
			return false;
		}
		level.setBlock(cell, torch, Block.UPDATE_ALL);
		walls.addBlock(cell.asLong(), TORCH_RING, 0);
		level.playSound(null, cell, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(cell).inflate(SWING_RANGE), v -> v.isAlive() && !v.isSleeping());
		if (!nearby.isEmpty()) {
			nearby.get(0).swing(InteractionHand.MAIN_HAND);
		}
		LVLog.detail(LVLog.Category.CARE, village, "torch at {}", cell);
		return true;
	}

	/** On natural, dry, sturdy ground; not a road, not a field or plot; room above for the post and the lantern. */
	private static boolean tryLampAt(ServerLevel level, VillageRecord village, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return false;
		}
		VillageWalls walls = village.getWalls();
		LVConfig config = LVConfig.get();
		BlockPos ground = new BlockPos(x, SiteFinder.groundTop(level, x, z), z);
		BlockPos base = ground.above();
		if (walls.getBlocks().containsKey(base.asLong()) || walls.getAbandoned().contains(base.asLong())) {
			return false; // already a post here, or a player cleared one
		}
		BlockState groundState = level.getBlockState(ground);
		if (!SiteFinder.isNaturalGround(groundState) || !groundState.getFluidState().isEmpty()
				|| !groundState.isFaceSturdy(level, ground, Direction.UP)
				|| RoadBuilder.roadBlocks().contains(groundState.getBlock())
				|| inField(village, x, z) || insidePlot(village, x, z)) {
			return false;
		}
		for (int dy = 1; dy <= config.lampHeight + 1; dy++) {
			if (!SiteFinder.isClearable(level.getBlockState(ground.above(dy)))) {
				return false;
			}
		}
		placeLamp(level, village, ground);
		removeNearbyTorch(level, village, ground);
		LVLog.detail(LVLog.Category.CARE, village, "lamp post at {}", base);
		return true;
	}

	/** The village's palisade material, lampHeight tall, a lantern on top; each block remembered on its own. */
	private static void placeLamp(ServerLevel level, VillageRecord village, BlockPos ground) {
		LVConfig config = LVConfig.get();
		VillageWalls walls = village.getWalls();
		WallShapes.Blocks4 blocks = WallShapes.blocks(village);
		BlockState post = blocks.palisade().defaultBlockState();
		for (int dy = 1; dy <= config.lampHeight; dy++) {
			BlockPos pos = ground.above(dy);
			level.setBlock(pos, post, Block.UPDATE_ALL);
			walls.addBlock(pos.asLong(), LAMP_RING, dy);
		}
		BlockPos lantern = ground.above(config.lampHeight + 1);
		level.setBlock(lantern, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
		walls.addBlock(lantern.asLong(), LAMP_RING, 0);
		level.playSound(null, lantern, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(lantern).inflate(SWING_RANGE), v -> v.isAlive() && !v.isSleeping());
		if (!nearby.isEmpty()) {
			nearby.get(0).swing(InteractionHand.MAIN_HAND);
		}
	}

	/** A loose torch within lampSpacing of a new post has done its job; at most one cleared per post (spec v4 mini-update M7). */
	private static void removeNearbyTorch(ServerLevel level, VillageRecord village, BlockPos lampGround) {
		VillageWalls walls = village.getWalls();
		int spacing = LVConfig.get().lampSpacing;
		Iterator<Long2IntMap.Entry> it = walls.getBlocks().long2IntEntrySet().iterator();
		while (it.hasNext()) {
			Long2IntMap.Entry entry = it.next();
			if (VillageWalls.ringOf(entry.getIntValue()) != TORCH_RING) {
				continue;
			}
			BlockPos torchPos = BlockPos.of(entry.getLongKey());
			if (torchPos.distManhattan(lampGround) <= spacing) {
				level.removeBlock(torchPos, false);
				it.remove();
				return;
			}
		}
	}

	/** Inside a plot (any village's) or the running project of this one. */
	private static boolean insidePlot(VillageRecord village, int x, int z) {
		for (BoundingBox plot : village.getPlots()) {
			if (plot.isInside(x, plot.minY(), z)) {
				return true;
			}
		}
		BuildProject project = village.getProject();
		return project != null && project.getFootprint().isInside(x, project.getFootprint().minY(), z);
	}

	/**
	 * A lamp block lost without a player breaking it (explosion, fire): the whole post is forgotten so tickLamps
	 * builds a fresh one next time it passes this spot (spec v4 mini-update M7).
	 */
	public static void onLampLost(VillageWalls walls, BlockPos pos) {
		walls.getBlocks().long2IntEntrySet().removeIf(entry -> {
			if (VillageWalls.ringOf(entry.getIntValue()) != LAMP_RING) {
				return false;
			}
			BlockPos other = BlockPos.of(entry.getLongKey());
			return other.getX() == pos.getX() && other.getZ() == pos.getZ();
		});
	}

	private static boolean besideRoad(ServerLevel level, int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) {
					BlockPos pos = new BlockPos(x + dx, SiteFinder.groundTop(level, x + dx, z + dz), z + dz);
					if (RoadBuilder.roadBlocks().contains(level.getBlockState(pos).getBlock())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean onSquare(VillageRecord village, int x, int z, LVConfig config) {
		return village.horizontalDistSqr(new BlockPos(x, 0, z)) <= (double) config.plazaRadius * config.plazaRadius;
	}

	/** Inside the outermost wall, within the walkway kept free along it. */
	private static boolean alongWall(VillageRecord village, int x, int z, LVConfig config) {
		WallRing ring = village.getWalls().outer();
		if (ring == null || !ring.contains(x + 0.5, z + 0.5)) {
			return false;
		}
		int reach = config.wallInnerBuffer;
		for (int i = 0; i < ring.size(); i++) {
			if (Math.abs(ring.x(i) - x) <= reach && Math.abs(ring.z(i) - z) <= reach) {
				return true;
			}
		}
		return false;
	}

	private static boolean inField(VillageRecord village, int x, int z) {
		for (BoundingBox field : village.getFieldPlots()) {
			if (field.isInside(x, field.minY(), z)) {
				return true;
			}
		}
		return false;
	}

	private static boolean nearTorch(VillageWalls walls, BlockPos cell, int spacing) {
		for (Long2IntMap.Entry entry : walls.getBlocks().long2IntEntrySet()) {
			if (VillageWalls.ringOf(entry.getIntValue()) == TORCH_RING && BlockPos.of(entry.getLongKey()).distManhattan(cell) < spacing) {
				return true;
			}
		}
		return false;
	}
}
