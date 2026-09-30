package com.khanhvu.livingvillages.command;

import com.khanhvu.livingvillages.board.MaterialBoard;
import com.khanhvu.livingvillages.board.MaterialRequest;
import com.khanhvu.livingvillages.build.BlockPlacer;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildingKind;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.chronicle.ChronicleEntry;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.festival.Festival;
import com.khanhvu.livingvillages.identity.NamePool;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.society.VillageLeader;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.society.VillageNeeds;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.village.VillageType;
import com.khanhvu.livingvillages.work.ProfessionWork;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * /livingvillages command tree. Every subcommand requires permission level 2, except {@code chronicle}.
 */
public final class LVCommands {
	private static final int CHRONICLE_LINES = 10;

	private LVCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerTree(dispatcher));
	}

	private static void registerTree(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("livingvillages")
				.then(Commands.literal("chronicle").executes(LVCommands::chronicle))
				.then(op("status").executes(LVCommands::status))
				.then(op("list").executes(LVCommands::list))
				.then(op("build")
						.executes(ctx -> build(ctx, false))
						.then(Commands.literal("instant").executes(ctx -> build(ctx, true))))
				.then(op("cancel").executes(LVCommands::cancel))
				.then(op("festival").executes(LVCommands::festival))
				.then(Commands.literal("board")
						.executes(LVCommands::board)
						.then(op("place").executes(LVCommands::boardPlace)))
				.then(op("pause").executes(ctx -> setEnabled(ctx, false)))
				.then(op("resume").executes(ctx -> setEnabled(ctx, true)))
				.then(op("reload").executes(LVCommands::reload))
				.then(op("rename")
						.then(Commands.argument("name", StringArgumentType.greedyString()).executes(LVCommands::rename)))
				.then(op("speed")
						.then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.1, 10.0))
								.executes(LVCommands::speed)))
				.then(op("templates")
						.executes(LVCommands::templatesForNearest)
						.then(Commands.argument("type", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										Arrays.stream(VillageType.values()).map(VillageType::getSerializedName), builder))
								.executes(LVCommands::templatesForType))));
	}

	/** Starts a festival in the nearest village now, for testing (spec v2-GĐ 8). */
	private static int festival(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		if (Festival.isRunning(record)) {
			source.sendFailure(LVText.tr("livingvillages.command.festival.already"));
			return 0;
		}
		Festival.start(source.getLevel(), record);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.festival.started", VillageIdentity.displayName(record)), true);
		return 1;
	}

	/** The nearest village's material requests (spec v2-GĐ 9); open to every player. */
	private static int board(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		source.sendSuccess(() -> LVText.tr("livingvillages.command.board.header", VillageIdentity.displayName(record)), false);
		sendRequests(source, record);
		return record.getRequests().size();
	}

	private static void sendRequests(CommandSourceStack source, VillageRecord record) {
		List<MaterialRequest> requests = record.getRequests();
		if (requests.isEmpty()) {
			source.sendSuccess(() -> LVText.tr("livingvillages.board.enough"), false);
			return;
		}
		for (MaterialRequest request : requests) {
			String key = request.isFulfilled() ? "livingvillages.command.board.entry_done" : "livingvillages.command.board.entry";
			source.sendSuccess(() -> LVText.compose(key, MaterialBoard.requestLine(request), request.reward()), false);
		}
	}

	/** Places the board of the nearest village again (it is otherwise placed only once). */
	private static int boardPlace(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		if (!MaterialBoard.placeBoard(source.getLevel(), record)) {
			source.sendFailure(LVText.tr("livingvillages.command.board.no_spot"));
			return 0;
		}
		MaterialBoard.manage(source.getLevel(), record, null);
		BlockPos pos = record.getBoardPos();
		source.sendSuccess(() -> LVText.tr("livingvillages.command.board.placed", formatPos(pos)), true);
		return 1;
	}

	/** Subcommand for operators (permission level 2). */
	private static LiteralArgumentBuilder<CommandSourceStack> op(String name) {
		return Commands.literal(name).requires(source -> source.hasPermission(2));
	}

	/** The latest chronicle entries of the nearest village (spec v2-GĐ 7.2); open to every player. */
	private static int chronicle(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		List<ChronicleEntry> entries = record.getChronicle();
		source.sendSuccess(() -> LVText.tr("livingvillages.command.chronicle.header", VillageIdentity.displayName(record)), false);
		if (entries.isEmpty()) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.chronicle.empty"), false);
			return 0;
		}
		for (ChronicleEntry entry : entries.subList(Math.max(0, entries.size() - CHRONICLE_LINES), entries.size())) {
			source.sendSuccess(entry::render, false);
		}
		return entries.size();
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}

		LVConfig config = LVConfig.get();
		VillageAnalyzer.Stats stats = VillageAnalyzer.analyze(level, record);
		BlockPos bell = record.getBellPos();
		long cooldownLeft = record.getLastBuildTick() == VillageRecord.NEVER || record.isSkipCooldown()
				? 0
				: Math.max(0, record.getLastBuildTick() + config.cooldownTicks - level.getGameTime());

		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.header",
				VillageIdentity.displayName(record), formatPos(bell), typeName(record.getVillageType()), stateName(record)), false);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.villagers",
				stats.adultVillagers()), false);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.beds",
				stats.totalBeds(), stats.freeBeds()), false);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.houses",
				record.getHousesBuilt(), VillageLevel.buildLimit(record)), false);
		if (VillageLevel.enabled() && record.getLevel() >= 0) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.status.level",
					LVText.tr(VillageLevel.langKey(record.getLevel())), VillageLevel.buildLimit(record), VillageLevel.buildRadius(record)), false);
		}
		source.sendSuccess(() -> projectLine(level, record), false);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.cooldown",
				cooldownLeft, cooldownLeft / 20), false);
		if (config.needsEnabled) {
			statusSociety(source, level, record, stats);
		}
		if (config.boardEnabled) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.status.board"), false);
			sendRequests(source, record);
		}
		return 1;
	}

	/** Needs, mood and leader (spec v2-GĐ 2.5). Computed fresh here; the leader itself only changes in the ticker. */
	private static void statusSociety(CommandSourceStack source, ServerLevel level, VillageRecord record, VillageAnalyzer.Stats stats) {
		VillageNeeds needs = VillageNeeds.compute(level, record, stats);
		int mood = VillageMood.compute(record, needs, level.getGameTime());
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.needs",
				VillageSociety.bar(needs.housing()), VillageSociety.bar(needs.food()),
				VillageSociety.bar(needs.jobs()), VillageSociety.bar(needs.safety())), false);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.status.mood",
				LVText.tr(VillageMood.Level.of(mood).langKey()), mood), false);
		if (LVConfig.get().workEnabled) {
			ProfessionWork.Bonuses bonuses = ProfessionWork.bonuses(stats.adults());
			source.sendSuccess(() -> LVText.tr("livingvillages.command.status.work",
					bonuses.safety(), Math.round(bonuses.buildSpeed() * 100), bonuses.radius()), false);
		}
		Component leader = VillageLeader.displayName(level, record);
		Component wish = VillageSociety.describeWish(level, record, stats, needs);
		source.sendSuccess(() -> leader == null
				? LVText.tr("livingvillages.command.status.leader_none", wish)
				: LVText.tr("livingvillages.command.status.leader", leader, wish), false);
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<VillageRecord> villages = VillageRegistry.get(source.getLevel()).getVillages();
		source.sendSuccess(() -> LVText.tr("livingvillages.command.list.header", villages.size()), false);
		for (VillageRecord record : villages) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.list.entry",
					VillageIdentity.displayName(record) + " (" + formatPos(record.getBellPos()) + ")", typeName(record.getVillageType()),
					record.getHousesBuilt(), stateName(record)), false);
		}
		return villages.size();
	}

	/**
	 * Starts a project now, ignoring beds and cooldown (and past site failures). With {@code instant}, the project
	 * (new or already running) is placed at once for debugging.
	 */
	private static int build(CommandContext<CommandSourceStack> context, boolean instant) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		VillageRegistry registry = VillageRegistry.get(level);
		BuildProject project = record.getProject();
		if (project == null) {
			int limit = VillageLevel.buildLimit(record);
			if (record.getHousesBuilt() >= limit) {
				source.sendFailure(LVText.tr("livingvillages.command.build.max_houses", limit));
				return 0;
			}
			VillageAnalyzer.ensureVillageType(record, VillageAnalyzer.analyze(level, record), registry);
			VillageType type = VillageTicker.villageType(record);
			int houseCount = BuildingTemplateProvider.getHouses(level, type).size();
			if (houseCount == 0) {
				source.sendFailure(LVText.tr("livingvillages.command.build.no_template", typeName(type)));
				return 0;
			}
			record.setFailedSiteAttempts(0);
			record.setNextSiteAttemptTick(0);
			project = VillageTicker.startForced(level, registry, record);
			if (project == null) {
				source.sendFailure(LVText.tr("livingvillages.command.build.no_site", houseCount));
				return 0;
			}
		} else if (!instant) {
			source.sendFailure(LVText.tr("livingvillages.command.build.already", project.getTemplateId().toString()));
			return 0;
		}

		String houseId = project.getTemplateId().toString();
		String origin = formatPos(project.getOrigin());
		String rotation = project.getRotation().getSerializedName();
		if (!instant) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.build.started", houseId, origin, rotation), true);
			return 1;
		}
		BlockPlacer placer = VillageTicker.completeInstantly(level, registry, record);
		if (placer == null) {
			source.sendFailure(LVText.tr("livingvillages.command.build.template_missing", houseId));
			return 0;
		}
		source.sendSuccess(() -> LVText.tr("livingvillages.command.build.instant_done", houseId, origin,
				rotation, placer.getPlacedCount(), placer.getSkippedCount()), true);
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		BuildProject project = record.getProject();
		if (project == null) {
			source.sendFailure(LVText.tr("livingvillages.command.cancel.none"));
			return 0;
		}
		String houseId = project.getTemplateId().toString();
		VillageTicker.cancelProject(source.getLevel(), VillageRegistry.get(source.getLevel()), record);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.cancel.done", houseId), true);
		return 1;
	}

	private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
		LVConfig.get().enabled = enabled;
		LVConfig.save();
		context.getSource().sendSuccess(() -> LVText.tr(enabled ? "livingvillages.command.resume" : "livingvillages.command.pause"), true);
		return 1;
	}

	private static int rename(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		String name = StringArgumentType.getString(context, "name").trim();
		record.setName(name);
		VillageRegistry.get(source.getLevel()).setDirty();
		source.sendSuccess(() -> LVText.tr("livingvillages.command.rename.done", name), true);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		boolean ok = LVConfig.load();
		LVText.load(); // the language may have changed
		NamePool.load();
		if (ok) {
			source.sendSuccess(() -> LVText.tr("livingvillages.command.reload.done"), true);
			return 1;
		}
		source.sendFailure(LVText.tr("livingvillages.command.reload.failed"));
		return 0;
	}

	private static int speed(CommandContext<CommandSourceStack> context) {
		double multiplier = DoubleArgumentType.getDouble(context, "multiplier");
		LVConfig.get().speedMultiplier = multiplier;
		LVConfig.save();
		context.getSource().sendSuccess(() -> LVText.tr("livingvillages.command.speed", multiplier), true);
		return 1;
	}

	private static int templatesForNearest(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageRecord record = findNearestVillage(source);
		if (record == null) {
			source.sendFailure(LVText.tr("livingvillages.command.no_village"));
			return 0;
		}
		VillageType type = record.getVillageType() != null ? record.getVillageType() : VillageType.PLAINS;
		return listTemplates(source, type);
	}

	/** Debug variant: list houses of any village type without standing in such a village. */
	private static int templatesForType(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VillageType type = VillageType.byName(StringArgumentType.getString(context, "type"));
		if (type == null) {
			source.sendFailure(LVText.tr("livingvillages.command.templates.bad_type"));
			return 0;
		}
		return listTemplates(source, type);
	}

	private static int listTemplates(CommandSourceStack source, VillageType type) {
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(source.getLevel(), type);
		source.sendSuccess(() -> LVText.tr("livingvillages.command.templates.header",
				buildings.size(), typeName(type), type.getHousePool().toString()), false);
		for (BuildingKind kind : BuildingKind.values()) {
			for (BuildingTemplate building : BuildingTemplateProvider.ofKind(buildings, kind)) {
				BoundingBox box = building.contentBox();
				Component what = VillageSociety.buildingName(kind, building.profession());
				source.sendSuccess(() -> LVText.tr("livingvillages.command.templates.entry",
						what, building.id().getPath(), box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan(),
						building.bedCount(), building.weight(), building.floorY()), false);
			}
		}
		return buildings.size();
	}

	/** Nearest registered village to the command source, within activeRange. */
	@Nullable
	static VillageRecord findNearestVillage(CommandSourceStack source) {
		BlockPos pos = BlockPos.containing(source.getPosition());
		return VillageRegistry.get(source.getLevel()).findNearest(pos, LVConfig.get().activeRange);
	}

	private static Component projectLine(ServerLevel level, VillageRecord record) {
		BuildProject project = record.getProject();
		if (project == null) {
			return LVText.tr("livingvillages.command.status.project_none");
		}
		project.ensureSteps(level, VillageTicker.villageType(record));
		Villager builder = BuilderAssignment.getBuilder(level, project);
		Component builderName = builder != null ? builder.getName()
				: LVText.tr(project.getBuilderUuid() == null ? "livingvillages.command.status.builder_none" : "livingvillages.command.status.builder_away");
		return LVText.tr("livingvillages.command.status.project", project.getTemplateId().toString(),
				project.getProgressPercent(), builderName, project.getSkippedCount());
	}

	private static String formatPos(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private static Component typeName(@Nullable VillageType type) {
		return LVText.tr("livingvillages.type." + (type == null ? "unknown" : type.getSerializedName()));
	}

	private static Component stateName(VillageRecord record) {
		return LVText.tr(record.isActive() ? "livingvillages.state.active" : "livingvillages.state.inactive");
	}
}
