package com.khanhvu.livingvillages.tick;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BlockPlacer;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildSite;
import com.khanhvu.livingvillages.build.BuildStep;
import com.khanhvu.livingvillages.build.HouseTemplate;
import com.khanhvu.livingvillages.build.HouseTemplateProvider;
import com.khanhvu.livingvillages.build.Replanter;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.village.VillageScanner;
import com.khanhvu.livingvillages.village.VillageType;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main loop, driven by END_WORLD_TICK. Only villages with a player within activeRange do anything; far villages
 * and unloaded chunks simply pause, nothing is simulated to catch up.
 */
public final class VillageTicker {
	/** Wait after a failed site search before trying again (spec 6.5). */
	private static final int SITE_RETRY_TICKS = 6000;
	/** Unused build points are capped so a builder arriving late does not place a burst of blocks. */
	private static final double MAX_BUILD_POINTS = 4.0;
	/** Steps that change nothing (already cleared, ground already there) are free, but bounded per tick. */
	private static final int MAX_NOOP_STEPS_PER_TICK = 64;

	/** Global block budget, shared by all dimensions within one server tick. */
	private static int budgetServerTick = -1;
	private static int budgetLeft;

	private VillageTicker() {
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(VillageTicker::tick);
	}

	private static void tick(ServerLevel level) {
		LVConfig config = LVConfig.get();
		if (!config.enabled || level.players().isEmpty()) {
			return;
		}
		long time = level.getGameTime();
		if (time % config.scanIntervalTicks == 0) {
			VillageScanner.scan(level);
		}
		boolean manage = time % config.manageIntervalTicks == 0;
		boolean updateBuilders = time % BuilderAssignment.UPDATE_INTERVAL == 0;
		VillageRegistry registry = VillageRegistry.get(level);
		for (VillageRecord village : registry.getVillages()) {
			if (!village.isActive() || !level.isLoaded(village.getBellPos()) || !isNearPlayer(level, village, config.activeRange)) {
				continue;
			}
			if (manage) {
				VillageAnalyzer.Stats stats = config.needsEnabled ? VillageSociety.update(level, registry, village) : null;
				if (village.getProject() == null) {
					tryAutoStart(level, registry, village, time, stats);
				}
			}
			BuildProject project = village.getProject();
			if (project != null) {
				tickProject(level, registry, village, project, updateBuilders);
			}
			if (config.replantSaplings) {
				Replanter.tick(level, registry, village);
			}
		}
	}

	/** Starts a project when the village is full (spec 6.3). {@code stats} may be passed when already computed. */
	private static void tryAutoStart(ServerLevel level, VillageRegistry registry, VillageRecord village, long time,
			@Nullable VillageAnalyzer.Stats stats) {
		LVConfig config = LVConfig.get();
		if (village.getHousesBuilt() >= config.maxHousesPerVillage
				|| village.getFailedSiteAttempts() >= config.maxSiteFailures
				|| time < village.getNextSiteAttemptTick()
				|| (village.getLastBuildTick() != VillageRecord.NEVER && time - village.getLastBuildTick() < config.cooldownTicks)) {
			return;
		}
		if (stats == null) {
			stats = VillageAnalyzer.analyze(level, village);
			VillageAnalyzer.ensureVillageType(village, stats, registry);
		}
		if (stats.adultVillagers() < 2 || stats.freeBeds() > config.freeBedThreshold) {
			return;
		}
		startProject(level, registry, village, false);
	}

	/**
	 * Picks a house (weighted like the pool) and a site, and makes it the village project. With
	 * {@code tryAllTemplates} (commands), other houses are tried when the picked one fits nowhere.
	 * A failed search counts toward maxSiteFailures. Returns null if no house or no site was found.
	 */
	@Nullable
	public static BuildProject startProject(ServerLevel level, VillageRegistry registry, VillageRecord village, boolean tryAllTemplates) {
		LVConfig config = LVConfig.get();
		List<HouseTemplate> houses = HouseTemplateProvider.getHouses(level, villageType(village));
		HouseTemplate first = HouseTemplateProvider.pickRandom(houses, level.getRandom());
		if (first == null) {
			return null;
		}
		List<HouseTemplate> candidates = new ArrayList<>();
		candidates.add(first);
		if (tryAllTemplates) {
			for (HouseTemplate house : houses) {
				if (house != first) {
					candidates.add(house);
				}
			}
		}
		for (HouseTemplate house : candidates) {
			Optional<BuildSite> site = SiteFinder.find(level, village, house, level.getRandom());
			if (site.isPresent()) {
				BuildProject project = BuildProject.create(house, site.get());
				village.setProject(project);
				village.setFailedSiteAttempts(0);
				registry.setDirty();
				LivingVillages.debug("Village {} starts {} at {} ({}), {} tree(s) to fell", village.getId(), house.id(),
						site.get().origin(), site.get().rotation(), site.get().treeRoots().size());
				return project;
			}
		}
		village.setFailedSiteAttempts(village.getFailedSiteAttempts() + 1);
		village.setNextSiteAttemptTick(level.getGameTime() + SITE_RETRY_TICKS);
		registry.setDirty();
		if (village.getFailedSiteAttempts() == config.maxSiteFailures) {
			LivingVillages.LOGGER.info("[LivingVillages] Village at {} found no building site {} times in a row; "
					+ "it stops building until /livingvillages build", village.getBellPos(), config.maxSiteFailures);
		} else {
			LivingVillages.debug("Village {} found no site for {}", village.getId(), first.id());
		}
		return null;
	}

	private static void tickProject(ServerLevel level, VillageRegistry registry, VillageRecord village, BuildProject project,
			boolean updateBuilders) {
		LVConfig config = LVConfig.get();
		if (!project.ensureSteps(level, villageType(village))) {
			LivingVillages.LOGGER.warn("[LivingVillages] Template {} no longer exists, cancelling the project of the village at {}",
					project.getTemplateId(), village.getBellPos());
			cancelProject(level, registry, village);
			return;
		}
		if (project.isDone()) {
			finishProject(level, registry, village, project);
			return;
		}
		if (!isWorkTime(level) || level.getRaidAt(village.getBellPos()) != null) {
			return;
		}

		BuildStep step = project.currentStep();
		Villager builder = updateBuilders
				? BuilderAssignment.update(level, village, project, step)
				: BuilderAssignment.getBuilder(level, project);
		if (builder != null && !updateBuilders) {
			BuilderAssignment.walkTo(builder, step, project);
		}
		boolean builderless = project.getBuilderUuid() == null;
		if (!builderless && (builder == null || !BuilderAssignment.isInReach(builder, step, project))) {
			return; // the builder is on the way
		}
		double rate = config.blocksPerSecond * config.speedMultiplier / 20.0 * (builderless ? 0.5 : 1.0);
		project.setBuildPoints(Math.min(MAX_BUILD_POINTS, project.getBuildPoints() + rate));

		BlockPlacer placer = project.getPlacer(level);
		int noops = 0;
		boolean progressed = false;
		while (project.getBuildPoints() >= 1.0 && hasBudget(level)) {
			step = project.currentStep();
			if (step == null || !placer.isLoaded(step) || (builder != null && !BuilderAssignment.isInReach(builder, step, project))) {
				break;
			}
			int skippedBefore = placer.getSkippedCount();
			int changed = placer.execute(step);
			project.addSkipped(placer.getSkippedCount() - skippedBefore);
			project.addReplant(placer.drainFelledSaplings());
			BuildStep next = project.nextStep();
			project.advance();
			progressed = true;
			if (next == null || next.getClass() != step.getClass() || next.anchor().getY() != step.anchor().getY()) {
				placer.flushUpdates(); // a layer is complete
			}
			if (changed > 0) {
				project.setBuildPoints(project.getBuildPoints() - 1.0);
				budgetLeft--;
				if (builder != null) {
					BuilderAssignment.onPlaced(builder, step);
				}
			} else if (++noops >= MAX_NOOP_STEPS_PER_TICK) {
				break;
			}
			if (project.getSkippedCount() > config.maxSkippedRatio * project.getTotalBlocks()) {
				LivingVillages.LOGGER.warn("[LivingVillages] Too many blocked positions ({} of {}) building {} at {}, project cancelled",
						project.getSkippedCount(), project.getTotalBlocks(), project.getTemplateId(), project.getOrigin());
				cancelProject(level, registry, village);
				village.setNextSiteAttemptTick(level.getGameTime() + SITE_RETRY_TICKS);
				return;
			}
		}
		if (progressed) {
			registry.setDirty();
		}
		if (project.isDone()) {
			finishProject(level, registry, village, project);
		}
	}

	/**
	 * Places all remaining steps of the village project at once (debug command). Returns the placer for its
	 * counts, or null if the project cannot run.
	 */
	@Nullable
	public static BlockPlacer completeInstantly(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		BuildProject project = village.getProject();
		if (project == null || !project.ensureSteps(level, villageType(village))) {
			return null;
		}
		BlockPlacer placer = new BlockPlacer(level, false);
		for (BuildStep step = project.currentStep(); step != null; step = project.currentStep()) {
			if (!placer.isLoaded(step)) {
				break;
			}
			int skippedBefore = placer.getSkippedCount();
			placer.execute(step);
			project.addSkipped(placer.getSkippedCount() - skippedBefore);
			project.addReplant(placer.drainFelledSaplings());
			project.advance();
		}
		placer.flushUpdates();
		registry.setDirty();
		if (project.isDone()) {
			finishProject(level, registry, village, project);
		}
		return placer;
	}

	private static void finishProject(ServerLevel level, VillageRegistry registry, VillageRecord village, BuildProject project) {
		project.getPlacer(level).flushUpdates();
		BuilderAssignment.release(level, project);
		queueReplants(village, project);
		village.recordHouseBuilt(project.getFootprint(), level.getGameTime());
		VillageEvents.BUILDING_COMPLETED.post(new VillageEvents.BuildingCompleted(level, village, project.getTemplateId().toString()));
		village.setProject(null);
		registry.setDirty();
		LivingVillages.debug("Village {} finished {} ({} blocks skipped)", village.getId(), project.getTemplateId(), project.getSkippedCount());
	}

	/** Drops the project without touching the blocks already placed; the plot is not reserved. */
	public static void cancelProject(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		BuildProject project = village.getProject();
		if (project == null) {
			return;
		}
		project.getPlacer(level).flushUpdates();
		BuilderAssignment.release(level, project);
		queueReplants(village, project);
		village.setProject(null);
		registry.setDirty();
	}

	/** Trees felled for the project are replanted around it (spec v2-GĐ 1), even if it was cancelled. */
	private static void queueReplants(VillageRecord village, BuildProject project) {
		if (!LVConfig.get().replantSaplings) {
			return;
		}
		for (ResourceLocation sapling : project.getReplant()) {
			village.getPendingSaplings().add(new VillageRecord.PendingSapling(sapling, project.getFootprint().getCenter()));
		}
	}

	public static VillageType villageType(VillageRecord village) {
		return village.getVillageType() != null ? village.getVillageType() : VillageType.PLAINS;
	}

	public static boolean isWorkTime(ServerLevel level) {
		LVConfig config = LVConfig.get();
		long time = level.getDayTime() % 24000L;
		return config.workStartTime <= config.workEndTime
				? time >= config.workStartTime && time <= config.workEndTime
				: time >= config.workStartTime || time <= config.workEndTime;
	}

	private static boolean isNearPlayer(ServerLevel level, VillageRecord village, int range) {
		double rangeSqr = (double) range * range;
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator() && village.horizontalDistSqr(player.blockPosition()) <= rangeSqr) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasBudget(ServerLevel level) {
		int serverTick = level.getServer().getTickCount();
		if (serverTick != budgetServerTick) {
			budgetServerTick = serverTick;
			budgetLeft = LVConfig.get().maxBlocksPerTickGlobal;
		}
		return budgetLeft > 0;
	}
}
