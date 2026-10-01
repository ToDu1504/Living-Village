package com.khanhvu.livingvillages.road;

import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.mixin.SinglePoolElementAccessor;
import com.khanhvu.livingvillages.util.LVLog;
import com.khanhvu.livingvillages.village.PlotBuild;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.wall.CitySites;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Re-plans a world-gen village house that blocks a road the village has planned for itself (spec v4 mini-update
 * M5): the house is rebuilt from the exact same template on a street frontage, and only once the replacement
 * stands does the old one come down. <b>Quy tắc 6 is only half relaxed here</b>: a world-gen house may be taken
 * down under the strict conditions below, but anything a player has touched -- here or anywhere else -- is still
 * never touched. Off by default ({@code relocateVanillaHouses}): taking a world-gen building down cannot be
 * undone, and the "still original" check is block-type only (see {@link #isStillOriginal}).
 *
 * <p>One relocation at a time per village, never competing with the village's own current project or site search.
 * A piece found to be player-modified is remembered in {@link VillageRecord#getKeptPieces()} and never checked
 * again, even after a restart.
 */
public final class VillageRelocator {
	/** Give up on an attempt that never finishes (the search found no site, or the project was cancelled). */
	private static final int GIVE_UP_TICKS = 24000;

	private record Pending(ResourceLocation templateId, BlockPos oldOrigin, Rotation oldRotation, long startedTick) {
	}

	/** One relocation in flight per village; not saved, a restart simply tries again. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/** Day (dayTime / 24000) a village last started a relocation, for relocatePerDay; not saved. */
	private static final Map<UUID, Long> LAST_DAY = new HashMap<>();

	private VillageRelocator() {
	}

	public static boolean enabled() {
		return LVConfig.get().relocateVanillaHouses;
	}

	/** Whether this village has a relocation in flight: the normal auto-build must not start anything meanwhile. */
	public static boolean isBusy(VillageRecord village) {
		return PENDING.containsKey(village.getId());
	}

	public static void register() {
		VillageEvents.BUILDING_COMPLETED.register(e -> {
			Pending pending = PENDING.get(e.village().getId());
			if (pending == null || !e.templateId().equals(pending.templateId())) {
				return;
			}
			PENDING.remove(e.village().getId());
			PlotBuild newBuild = e.village().buildOf(e.footprint());
			if (newBuild != null) {
				finish(e.level(), VillageRegistry.get(e.level()), e.village(), pending, newBuild);
			}
		});
	}

	/** Called every manageIntervalTicks for villages near a player. */
	public static void tick(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		if (!enabled() || village.getFrame() == null || village.getLevel() < LVConfig.get().relocateMinLevel) {
			return;
		}
		Pending pending = PENDING.get(village.getId());
		if (pending != null) {
			if (level.getGameTime() - pending.startedTick() > GIVE_UP_TICKS) {
				PENDING.remove(village.getId()); // no site found, or the project was cancelled; try again later
			}
			return;
		}
		if (village.getProject() != null || CitySites.isScanning(village)) {
			return; // never compete with the village's own current build or search
		}
		long day = level.getDayTime() / 24000L;
		Long lastDay = LAST_DAY.get(village.getId());
		if (lastDay != null && lastDay == day) {
			return;
		}
		PoolElementStructurePiece piece = findMisaligned(level, village);
		if (piece == null) {
			return;
		}
		start(level, village, piece, day);
	}

	/**
	 * A world-gen house in the village's pool of houses, inside the frame, crossing a planned road, not already
	 * known to be player-modified, and found to still be original. Only one scope decision from the spec is
	 * implemented here: a house is misaligned when it blocks a planned road -- the broader "faces away from the
	 * street" case is not detected.
	 */
	@Nullable
	private static PoolElementStructurePiece findMisaligned(ServerLevel level, VillageRecord village) {
		int[] frame = village.getFrame();
		Set<ResourceLocation> houseIds = new HashSet<>();
		for (BuildingTemplate template : com.khanhvu.livingvillages.build.BuildingTemplateProvider.getBuildings(level,
				com.khanhvu.livingvillages.tick.VillageTicker.villageType(village))) {
			houseIds.add(template.id());
		}
		if (houseIds.isEmpty()) {
			return null;
		}
		LongOpenHashSet kept = village.getKeptPieces();
		StructureManager structures = level.structureManager();
		Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int cx = frame[0] >> 4; cx <= frame[2] >> 4; cx++) {
			for (int cz = frame[1] >> 4; cz <= frame[3] >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				for (StructureStart start : structures.startsForStructure(new ChunkPos(cx, cz), s -> true)) {
					if (start == StructureStart.INVALID_START || !seen.add(start)) {
						continue;
					}
					PoolElementStructurePiece found = checkStart(level, village, start, frame, houseIds, kept);
					if (found != null) {
						return found;
					}
				}
			}
		}
		return null;
	}

	@Nullable
	private static PoolElementStructurePiece checkStart(ServerLevel level, VillageRecord village, StructureStart start, int[] frame,
			Set<ResourceLocation> houseIds, LongOpenHashSet kept) {
		for (StructurePiece piece : start.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece pep) || !(pep.getElement() instanceof SinglePoolElement single)) {
				continue;
			}
			Optional<ResourceLocation> id = ((SinglePoolElementAccessor) single).livingvillages$getTemplate().left();
			if (id.isEmpty() || !houseIds.contains(id.get()) || kept.contains(pep.getPosition().asLong())) {
				continue; // not a houses-pool piece, or already known to be player-modified
			}
			BoundingBox box = pep.getBoundingBox();
			if (!insideFrame(frame, box) || !blocksRoad(village, box) || !isLoaded(level, box) || containsBell(level, village, box)) {
				continue;
			}
			BuildingTemplate template = Demolisher.templateById(level, village, id.get());
			if (template == null) {
				continue;
			}
			if (!isStillOriginal(level, template, pep.getPosition(), pep.getRotation())) {
				kept.add(pep.getPosition().asLong());
				continue;
			}
			return pep;
		}
		return null;
	}

	private static boolean insideFrame(int[] frame, BoundingBox box) {
		return box.minX() >= frame[0] && box.maxX() <= frame[2] && box.minZ() >= frame[1] && box.maxZ() <= frame[3];
	}

	/** Whether the piece crosses a road the village has planned for itself, paved or not (mirrors CitySites.crosses). */
	private static boolean blocksRoad(VillageRecord village, BoundingBox box) {
		int keep = LVConfig.get().roadWidth / 2 + 1;
		for (RoadSegment segment : village.getRoads().getSegments()) {
			if (box.intersects(Math.min(segment.x1(), segment.x2()) - keep, Math.min(segment.z1(), segment.z2()) - keep,
					Math.max(segment.x1(), segment.x2()) + keep, Math.max(segment.z1(), segment.z2()) + keep)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLoaded(ServerLevel level, BoundingBox box) {
		return level.hasChunk(box.minX() >> 4, box.minZ() >> 4) && level.hasChunk(box.maxX() >> 4, box.maxZ() >> 4);
	}

	/** The bell itself, or a meeting point POI, inside the piece: never touched no matter what else is true. */
	private static boolean containsBell(ServerLevel level, VillageRecord village, BoundingBox box) {
		if (box.isInside(village.getBellPos())) {
			return true;
		}
		BlockPos center = new BlockPos(box.getCenter());
		int radius = Math.max(box.getXSpan(), Math.max(box.getYSpan(), box.getZSpan())) / 2 + 1;
		return level.getPoiManager().findAll(h -> h.is(PoiTypes.MEETING), box::isInside, center, radius, PoiManager.Occupancy.ANY).findAny().isPresent();
	}

	/**
	 * Block-type match against the template, within relocateMaxDrift; a container with real items in it (not just
	 * an unopened loot table, which leaves it empty) is a player having been there and stops the check outright.
	 */
	private static boolean isStillOriginal(ServerLevel level, BuildingTemplate template, BlockPos origin, Rotation rotation) {
		int total = 0;
		int mismatched = 0;
		for (StructureTemplate.StructureBlockInfo info : template.blocks()) {
			if (info.state().isAir()) {
				continue;
			}
			total++;
			BlockPos pos = BuildingTemplate.toWorld(info.pos(), origin, rotation);
			BlockState placed = info.state().rotate(rotation);
			BlockState present = level.getBlockState(pos);
			if (present.getBlock() != placed.getBlock()) {
				mismatched++;
			} else if (present.getBlock() instanceof EntityBlock && level.getBlockEntity(pos) instanceof Container container && !container.isEmpty()) {
				return false;
			}
		}
		return total == 0 || (double) mismatched / total <= LVConfig.get().relocateMaxDrift;
	}

	private static void start(ServerLevel level, VillageRecord village, PoolElementStructurePiece piece, long day) {
		if (!(piece.getElement() instanceof SinglePoolElement single)) {
			return;
		}
		Optional<ResourceLocation> id = ((SinglePoolElementAccessor) single).livingvillages$getTemplate().left();
		BuildingTemplate template = id.isEmpty() ? null : Demolisher.templateById(level, village, id.get());
		if (template == null) {
			return;
		}
		PENDING.put(village.getId(), new Pending(id.get(), piece.getPosition(), piece.getRotation(), level.getGameTime()));
		LAST_DAY.put(village.getId(), day);
		CitySites.request(level, village, template, List.of(template));
		LVLog.milestone(LVLog.Category.PLAN, village, "relocating {} from {}: it blocks the road plan", id.get(), piece.getPosition());
	}

	/** The replacement stands: move what is in its containers across, then take the old one down. */
	private static void finish(ServerLevel level, VillageRegistry registry, VillageRecord village, Pending pending, PlotBuild newBuild) {
		BuildingTemplate template = Demolisher.templateById(level, village, pending.templateId());
		if (template == null || !isLoaded(level, template.worldBox(pending.oldOrigin(), pending.oldRotation()))) {
			return; // the old building is simply left standing; a known, accepted gap rather than risk it half gone
		}
		if (!transferContainers(level, template, pending.oldOrigin(), pending.oldRotation(), newBuild.origin(), newBuild.rotation())) {
			LVLog.milestone(LVLog.Category.PLAN, village, "kept the old {} at {}: its containers would not fit in the new one",
					pending.templateId(), pending.oldOrigin());
			return;
		}
		int[] counts = Demolisher.removeTemplateBlocks(level, template, pending.oldOrigin(), pending.oldRotation());
		if (counts == null) {
			return;
		}
		registry.setDirty();
		Chronicle.add(level, village, Chronicle.Notice.BIG, "livingvillages.chronicle.relocated");
		LVLog.milestone(LVLog.Category.PLAN, village, "relocated {}: from {} to {}, {} blocks removed, {} left as found",
				pending.templateId(), pending.oldOrigin(), newBuild.origin(), counts[0], counts[1]);
	}

	/** Every non-empty container of the old building must have a matching container free at the new one. */
	private static boolean transferContainers(ServerLevel level, BuildingTemplate template, BlockPos oldOrigin, Rotation oldRotation,
			BlockPos newOrigin, Rotation newRotation) {
		for (StructureTemplate.StructureBlockInfo info : template.blocks()) {
			if (info.state().isAir() || !(info.state().getBlock() instanceof EntityBlock)) {
				continue;
			}
			BlockPos oldPos = BuildingTemplate.toWorld(info.pos(), oldOrigin, oldRotation);
			if (!(level.getBlockEntity(oldPos) instanceof Container from) || from.isEmpty()) {
				continue;
			}
			BlockPos newPos = BuildingTemplate.toWorld(info.pos(), newOrigin, newRotation);
			if (!(level.getBlockEntity(newPos) instanceof Container to) || !fits(from, to)) {
				return false;
			}
		}
		for (StructureTemplate.StructureBlockInfo info : template.blocks()) {
			if (info.state().isAir() || !(info.state().getBlock() instanceof EntityBlock)) {
				continue;
			}
			BlockPos oldPos = BuildingTemplate.toWorld(info.pos(), oldOrigin, oldRotation);
			if (level.getBlockEntity(oldPos) instanceof Container from && !from.isEmpty()
					&& level.getBlockEntity(BuildingTemplate.toWorld(info.pos(), newOrigin, newRotation)) instanceof Container to) {
				move(from, to);
			}
		}
		return true;
	}

	private static boolean fits(Container from, Container to) {
		for (int i = 0; i < from.getContainerSize(); i++) {
			if (!from.getItem(i).isEmpty() && (i >= to.getContainerSize() || !to.getItem(i).isEmpty())) {
				return false;
			}
		}
		return true;
	}

	private static void move(Container from, Container to) {
		for (int i = 0; i < from.getContainerSize(); i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty()) {
				to.setItem(i, stack);
				from.setItem(i, ItemStack.EMPTY);
			}
		}
		from.setChanged();
		to.setChanged();
	}

	/** Forgets in-flight relocations and the daily quota (server stop); a restart simply tries again. */
	public static void clear() {
		PENDING.clear();
		LAST_DAY.clear();
	}
}
