package com.khanhvu.livingvillages.work;

import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.util.LVLog;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lets a villager claim a job site beyond vanilla's 48-block {@code AcquirePoi} range, inside the village's
 * frame plus its farm belt (spec v4 mini-update M4): with ruined and pens placed outside the city wall, a
 * jobless villager inside it never sees them, and never becomes a farmer/shepherd/etc. This does not replace
 * vanilla: a villager still claims any job site within 48 blocks on its own first. It only reaches further,
 * for the same village's own people, by writing {@link MemoryModuleType#POTENTIAL_JOB_SITE} and letting
 * vanilla's {@code GoToPotentialJobSite}/{@code AssignProfessionFromJobSite} do the walking and the hiring.
 */
public final class JobSiteMatcher {
	private static final int MAX_MATCHES_PER_RUN = 2;
	private static final int VERTICAL_RANGE = 24;
	/** Vanilla's GoToPotentialJobSite gives up after 1200 ticks; checked a little later to be sure it has settled. */
	private static final long CHECK_DELAY_TICKS = 1220;
	/** A pair found to have failed is not retried before this long: about a day. */
	private static final long RETRY_COOLDOWN_TICKS = 24000;

	/** Failed (villager, job site) pairs; not saved, a restart simply retries sooner. */
	private static final Map<UUID, Map<Long, Long>> FAILED = new HashMap<>();
	/** Matches made, waiting to see whether vanilla hired the villager or gave up; not saved. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private record Pending(long matchedAtTick, BlockPos site) {
	}

	private JobSiteMatcher() {
	}

	public static boolean enabled() {
		return LVConfig.get().jobSitesInCity;
	}

	/** Called every manageIntervalTicks for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		if (!enabled()) {
			return;
		}
		long now = level.getGameTime();
		checkPending(level, now);
		int[] frame = village.getFrame();
		if (frame == null) {
			return; // a v3 world without a frame: vanilla's own range is all there is
		}
		LVConfig config = LVConfig.get();
		int belt = config.farmBeltWidth;
		int minX = frame[0] - belt;
		int minZ = frame[1] - belt;
		int maxX = frame[2] + belt;
		int maxZ = frame[3] + belt;
		BlockPos bell = village.getBellPos();
		int radius = Math.max((maxX - minX) / 2, (maxZ - minZ) / 2) + belt + 1;

		List<BlockPos> sites = level.getPoiManager()
				.findAll(h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), pos -> inside(pos, minX, minZ, maxX, maxZ), bell, radius, PoiManager.Occupancy.HAS_SPACE)
				.map(BlockPos::immutable)
				.toList();
		if (sites.isEmpty()) {
			return;
		}
		AABB box = new AABB(minX, bell.getY() - VERTICAL_RANGE, minZ, maxX + 1, bell.getY() + VERTICAL_RANGE, maxZ + 1);
		UUID builder = village.getProject() != null ? village.getProject().getBuilderUuid() : null;
		List<Villager> jobless = new ArrayList<>(level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby()
				&& v.getVillagerData().getProfession() == VillagerProfession.NONE
				&& !v.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)
				&& !v.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)
				&& !v.getUUID().equals(builder) && !v.getUUID().equals(village.getLeaderUuid())));
		if (jobless.isEmpty()) {
			return;
		}

		int matches = 0;
		for (BlockPos site : sites) {
			if (matches >= MAX_MATCHES_PER_RUN) {
				break;
			}
			if (anyJoblessWithinVanillaRange(jobless, site)) {
				continue; // vanilla's own AcquirePoi has this one covered, and does it better; see below
			}
			Villager nearest = nearestFree(jobless, site, now);
			if (nearest == null) {
				continue;
			}
			jobless.remove(nearest);
			if (match(level, village, nearest, site, now)) {
				matches++;
			}
		}
	}

	/**
	 * Resolves matches made at least CHECK_DELAY_TICKS ago: hired is a success (vanilla's own GoToPotentialJobSite
	 * already cleaned up after itself in that case). Anything else is a failure, and the cleanup is ours to do:
	 * GoToPotentialJobSite only erases POTENTIAL_JOB_SITE and releases the site's POI ticket from its own
	 * {@code stop()}, which never runs if the villager's schedule never once let the behavior start (the villager
	 * busy with something else the whole time) -- without this, the villager stays stuck "going" nowhere forever
	 * and the site's ticket is never freed for anyone else to claim, including the mod's own re-match.
	 */
	private static void checkPending(ServerLevel level, long now) {
		PENDING.entrySet().removeIf(entry -> {
			if (now - entry.getValue().matchedAtTick() < CHECK_DELAY_TICKS) {
				return false;
			}
			BlockPos site = entry.getValue().site();
			if (level.getEntity(entry.getKey()) instanceof Villager villager && !villager.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) {
				onFailed(entry.getKey(), site, now);
				villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
				if (level.getPoiManager().exists(site, h -> true)) {
					level.getPoiManager().release(site);
				}
			}
			return true;
		});
	}

	private static boolean inside(BlockPos pos, int minX, int minZ, int maxX, int maxZ) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	/**
	 * Whether some jobless villager is already close enough that vanilla's own {@code AcquirePoi} will scan this
	 * site (it looks {@link AcquirePoi#SCAN_RANGE} blocks around each villager). Such a site must be left alone:
	 * vanilla claims it within seconds and walks there with a real path, while taking it here would drop its POI
	 * ticket to zero, make vanilla skip it as occupied, and leave the villager depending on
	 * {@code GoToPotentialJobSite} -- which does not run at all unless the villager's current activity happens to
	 * allow it. Doing that to nearby sites is what kept a whole village jobless: every site got taken, nobody was
	 * ever hired, the ticket was released a thousand ticks later, and the same race started over.
	 */
	private static boolean anyJoblessWithinVanillaRange(List<Villager> jobless, BlockPos site) {
		double range = AcquirePoi.SCAN_RANGE;
		for (Villager villager : jobless) {
			if (villager.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5) <= range * range) {
				return true;
			}
		}
		return false;
	}

	/** The jobless villager closest to {@code site} that is not a pair remembered as failed recently. */
	private static Villager nearestFree(List<Villager> jobless, BlockPos site, long now) {
		return jobless.stream()
				.filter(v -> !recentlyFailed(v.getUUID(), site, now))
				.min(Comparator.comparingDouble(v -> v.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5)))
				.orElse(null);
	}

	private static boolean recentlyFailed(UUID villager, BlockPos site, long now) {
		Map<Long, Long> perSite = FAILED.get(villager);
		if (perSite == null) {
			return false;
		}
		Long since = perSite.get(site.asLong());
		return since != null && now - since < RETRY_COOLDOWN_TICKS;
	}

	/** Takes the job site and points the villager at it; vanilla walks it there and assigns the profession. */
	private static boolean match(ServerLevel level, VillageRecord village, Villager villager, BlockPos site, long now) {
		PoiManager poi = level.getPoiManager();
		boolean taken = poi.take(h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), (holder, pos) -> pos.equals(site), site, 1).isPresent();
		if (!taken) {
			return false;
		}
		villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(level.dimension(), site));
		PENDING.put(villager.getUUID(), new Pending(now, site));
		LVLog.detail(LVLog.Category.WORK, village, "matched jobless villager {} to distant job site at {}", villager.getUUID(), site);
		return true;
	}

	private static void onFailed(UUID villager, BlockPos site, long now) {
		FAILED.computeIfAbsent(villager, k -> new HashMap<>()).put(site.asLong(), now);
	}

	/** Forgets failed pairs and pending matches (server stop); a restart simply tries again. */
	public static void clear() {
		FAILED.clear();
		PENDING.clear();
	}
}
