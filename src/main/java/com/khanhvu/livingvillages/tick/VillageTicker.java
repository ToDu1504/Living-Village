package com.khanhvu.livingvillages.tick;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.board.MaterialBoard;
import com.khanhvu.livingvillages.build.BlockPlacer;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildSite;
import com.khanhvu.livingvillages.build.BuildStep;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.build.Replanter;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.identity.LevelUpFireworks;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.society.BuildDecision;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.village.VillageScanner;
import com.khanhvu.livingvillages.village.VillageType;
import com.khanhvu.livingvillages.voice.VillageVoice;
import com.khanhvu.livingvillages.wall.WallBuilder;
import com.khanhvu.livingvillages.work.ProfessionWork;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
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
		if (config.workEnabled) {
			ProfessionWork.tickTasks(level);
		}
		if (updateBuilders) {
			VillageIdentity.greetPlayers(level, registry);
		}
		for (VillageRecord village : registry.getVillages()) {
			if (!village.isActive() || !level.isLoaded(village.getBellPos()) || !isNearPlayer(level, village, config.activeRange)) {
				continue;
			}
			if (manage) {
				VillageAnalyzer.Stats stats = config.needsEnabled ? VillageSociety.update(level, registry, village) : null;
				if (stats != null && Chronicle.enabled()) {
					Chronicle.manage(level, village, stats);
				}
				if (!config.needsEnabled) {
					VillageIdentity.ensureName(village, villageType(village), level);
					VillageIdentity.nameVillagers(level, village);
				}
				MaterialBoard.manage(level, village, stats); // plans the next building first, so auto-start builds that one
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
			if (config.workEnabled) {
				ProfessionWork.tick(level, village);
			}
			if (config.voiceEnabled) {
				VillageVoice.tick(level, village);
			}
			LevelUpFireworks.tick(level, village);
			MaterialBoard.tick(level, village); // also takes the offers back when the board is switched off
			RoadBuilder.tick(level, village);
			WallBuilder.tick(level, registry, village, manage);
		}
	}

	/**
	 * Starts a project when the village needs one: by the leader's decision (spec v2-GĐ 3.2) when needs are enabled,
	 * otherwise when the village is full (0.1 rule). {@code stats} may be passed when already computed.
	 */
	private static void tryAutoStart(ServerLevel level, VillageRegistry registry, VillageRecord village, long time,
			@Nullable VillageAnalyzer.Stats stats) {
		LVConfig config = LVConfig.get();
		if (village.getHousesBuilt() >= VillageLevel.buildLimit(village)
				|| village.getFailedSiteAttempts() >= config.maxSiteFailures
				|| time < village.getNextSiteAttemptTick()
				|| (village.getLastBuildTick() != VillageRecord.NEVER && time - village.getLastBuildTick() < config.cooldownTicks
						&& !village.isSkipCooldown())) {
			return;
		}
		if (stats == null) {
			stats = VillageAnalyzer.analyze(level, village);
			VillageAnalyzer.ensureVillageType(village, stats, registry);
		}
		if (stats.adultVillagers() < 2) {
			return;
		}
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(level, villageType(village));
		if (config.needsEnabled && village.getNeeds() != null) {
			BuildDecision decision = BuildDecision.decide(level, village, stats, village.getNeeds(), buildings);
			if (decision.builds()) {
				startProject(level, registry, village, decision.candidates(buildings), false);
			}
		} else if (stats.freeBeds() <= config.freeBedThreshold) {
			startProject(level, registry, village, BuildingTemplateProvider.ofKind(buildings, BuildingKind.HOUSE), false);
		}
	}

	/**
	 * Command {@code build}: what the leader would build now (a house if nothing is needed), ignoring cooldown and
	 * past site failures. Every matching template is tried.
	 */
	@Nullable
	public static BuildProject startForced(ServerLevel level, VillageRegistry registry, VillageRecord village) {
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(level, villageType(village));
		List<BuildingTemplate> pool = BuildingTemplateProvider.ofKind(buildings, BuildingKind.HOUSE);
		if (LVConfig.get().needsEnabled) {
			VillageAnalyzer.Stats stats = VillageSociety.update(level, registry, village);
			BuildDecision decision = BuildDecision.decide(level, village, stats, village.getNeeds(), buildings);
			if (decision.builds()) {
				pool = decision.candidates(buildings);
			}
		}
		return startProject(level, registry, village, pool, true);
	}

	/**
	 * Picks a house (weighted like the pool) and a site, and makes it the village project. With
	 * {@code tryAllTemplates} (commands), other houses are tried when the picked one fits nowhere.
	 * A failed search counts toward maxSiteFailures. Returns null if no house or no site was found.
	 */
	@Nullable
	public static BuildProject startProject(ServerLevel level, VillageRegistry registry, VillageRecord village,
			List<BuildingTemplate> houses, boolean tryAllTemplates) {
		LVConfig config = LVConfig.get();
		// The building planned ahead for the material board comes first, if it is one of the candidates.
		BuildingTemplate first = houses.stream().filter(b -> b.id().equals(village.getPlannedTemplate())).findFirst()
				.orElseGet(() -> BuildingTemplateProvider.pickRandom(houses, level.getRandom()));
		if (first == null) {
			return null;
		}
		List<BuildingTemplate> candidates = new ArrayList<>();
		candidates.add(first);
		if (tryAllTemplates) {
			for (BuildingTemplate house : houses) {
				if (house != first) {
					candidates.add(house);
				}
			}
		}
		for (BuildingTemplate house : candidates) {
			Optional<BuildSite> site = SiteFinder.find(level, village, house, level.getRandom());
			if (site.isPresent()) {
				BuildProject project = BuildProject.create(house, site.get());
				village.setProject(project);
				village.setFailedSiteAttempts(0);
				village.setPlannedTemplate(null);
				village.setSkipCooldown(false);
				registry.setDirty();
				LivingVillages.debug("Village {} starts {} at {} ({}), {} tree(s) to fell", village.getId(), house.id(),
						site.get().origin(), site.get().rotation(), site.get().treeRoots().size());
				return project;
			}
		}
		village.setFailedSiteAttempts(village.getFailedSiteAttempts() + 1);
		village.setNextSiteAttemptTick(level.getGameTime() + SITE_RETRY_TICKS);
		village.setPlannedTemplate(null); // plan another design next time
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
		// Every board request for this building delivered: twice as fast (spec v2-GĐ 9.4).
		double boardFactor = project.getTemplateId().equals(village.getBoostTemplate()) ? 2.0 : 1.0;
		double rate = config.blocksPerSecond * config.speedMultiplier / 20.0 * (builderless ? 0.5 : 1.0) * moodSpeedFactor(village) * boardFactor
				* (1.0 + village.getBonuses().buildSpeed());
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
		if (project.getTemplateId().equals(village.getBoostTemplate())) {
			village.setBoostTemplate(null);
			village.setSkipCooldown(true); // the next building starts without waiting
		}
		BuildingTemplate building = project.getHouse();
		BuildingKind kind = building == null ? null : building.kind();
		if (kind == BuildingKind.PEN) {
			stockPen(level, village, project);
		}
		VillageEvents.BUILDING_COMPLETED.post(new VillageEvents.BuildingCompleted(level, village, project.getTemplateId(), kind,
				project.getFootprint()));
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

	/** Mood changes building speed (spec v2-GĐ 2.2): happy ×1.25, miserable ×0.75. */
	private static double moodSpeedFactor(VillageRecord village) {
		if (!LVConfig.get().needsEnabled || village.getMood() < 0) {
			return 1.0;
		}
		return switch (VillageMood.Level.of(village.getMood())) {
			case HAPPY -> 1.25;
			case MISERABLE -> 0.75;
			case NORMAL -> 1.0;
		};
	}

	/**
	 * A new pen gets a breeding pair, like a vanilla village pen: the animal of a keeper profession the village has
	 * (sheep, pig, cow, chicken), sheep otherwise. Vanilla pens get their animals from the village generator, which
	 * this mod does not run.
	 */
	private static void stockPen(ServerLevel level, VillageRecord village, BuildProject project) {
		List<Villager> adults = VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village));
		EntityType<? extends Animal> type = BuildDecision.animalForPen(adults);
		BlockPos center = project.getFootprint().getCenter();
		BlockPos spawn = new BlockPos(center.getX(), project.getFloorY(), center.getZ());
		for (int i = 0; i < 2; i++) {
			type.spawn(level, spawn, MobSpawnType.EVENT);
		}
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

	/** Takes one block from the global per-tick budget (walls share it with buildings); false when it is used up. */
	public static boolean useBudget(ServerLevel level) {
		if (!hasBudget(level)) {
			return false;
		}
		budgetLeft--;
		return true;
	}

	public static boolean hasBudget(ServerLevel level) {
		int serverTick = level.getServer().getTickCount();
		if (serverTick != budgetServerTick) {
			budgetServerTick = serverTick;
			budgetLeft = LVConfig.get().maxBlocksPerTickGlobal;
		}
		return budgetLeft > 0;
	}
}
