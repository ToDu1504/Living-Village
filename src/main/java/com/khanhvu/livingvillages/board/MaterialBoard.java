package com.khanhvu.livingvillages.board;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildingTemplate;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.build.SiteFinder;
import com.khanhvu.livingvillages.chronicle.Graveyard;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.society.BuildDecision;
import com.khanhvu.livingvillages.society.VillageLeader;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The material board (spec v2-GĐ 9): the village asks for the materials of the building it is building or will
 * build next, and players trade them to the leader for emeralds through the vanilla trading screen. The village
 * never depends on it; deliveries only speed it up.
 * <p>
 * Each request is an offer added to the leader's trades: cost = the material, result = emeralds, one use, price
 * multiplier 0 so reputation and demand never change the amount. A used offer means the request was delivered.
 * {@code Villager.restock()} resets the uses of every offer, so while the leader trades the offers are checked
 * every tick, and delivered ones are removed as soon as the trading screen closes (removing them while it is open
 * would shift the offer indexes the client uses).
 */
public final class MaterialBoard {
	private static final int SYNC_INTERVAL = 20;
	private static final int TRADE_XP = 5;
	private static final int MIN_COUNT = 8;
	private static final int MAX_COUNT = 64;
	private static final int BOARD_MIN_RADIUS = 2;
	private static final int BOARD_MAX_RADIUS = 4;
	private static final int BOARD_VERTICAL_RANGE = 3;

	/** Leader in a trading session → the trading player's name (known even after the screen closes). */
	private static final Map<UUID, String> TRADERS = new HashMap<>();
	/** Last text written on each board, to update signs only when the requests change. Not saved. */
	private static final Map<UUID, String> SIGN_TEXT = new HashMap<>();

	private MaterialBoard() {
	}

	public static void register() {
		// A new leader takes the requests over: the offers leave the old leader (if still around).
		VillageEvents.LEADER_CHANGED.register(e -> {
			if (e.oldLeader() != null) {
				removeOffers(e.oldLeader(), request -> true);
			}
		});
	}

	// ---------------------------------------------------------------- every tick

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		Villager leader = VillageLeader.getLeader(level, village);
		if (!config.boardEnabled) {
			if (!village.getRequests().isEmpty() && leader != null && leader.getTradingPlayer() == null) {
				removeOffers(leader, request -> true); // switched off: take every board offer back
				village.getRequests().clear();
				village.setRequestTemplate(null, 0);
				VillageRegistry.get(level).setDirty();
			}
			return;
		}
		if (leader == null) {
			return;
		}
		Player trader = leader.getTradingPlayer();
		if (trader != null) {
			TRADERS.put(leader.getUUID(), trader.getName().getString());
			checkDelivered(level, village, leader, trader.getName().getString());
			return;
		}
		String lastTrader = TRADERS.remove(leader.getUUID());
		if (lastTrader != null || level.getGameTime() % SYNC_INTERVAL == 0) {
			checkDelivered(level, village, leader, lastTrader);
			syncOffers(village, leader);
		}
	}

	/** Used board offers are delivered requests (the offer itself is removed once no screen is open). */
	private static void checkDelivered(ServerLevel level, VillageRecord village, Villager leader, @Nullable String player) {
		List<MaterialRequest> requests = village.getRequests();
		for (MaterialRequest request : requests) {
			if (request.isFulfilled()) {
				continue;
			}
			MerchantOffer offer = findOffer(leader.getOffers(), request);
			if (offer != null && offer.getUses() >= offer.getMaxUses()) {
				request.setFulfilled(true);
				boolean allDone = requests.stream().allMatch(MaterialRequest::isFulfilled);
				if (allDone) {
					village.setBoostTemplate(village.getRequestTemplate());
				}
				VillageRegistry.get(level).setDirty();
				LivingVillages.debug("Village {}: {} delivered {} {}", village.getId(), player, request.count(), request.itemId());
				VillageEvents.REQUEST_FULFILLED.post(new VillageEvents.RequestFulfilled(level, village, player, request.itemId(),
						request.count(), allDone));
			}
		}
	}

	/** Open requests are offered by the leader; delivered and outdated board offers are removed. */
	private static void syncOffers(VillageRecord village, Villager leader) {
		MerchantOffers offers = leader.getOffers();
		List<MaterialRequest> open = village.getRequests().stream().filter(r -> !r.isFulfilled()).toList();
		offers.removeIf(offer -> isBoardOffer(offer) && open.stream().noneMatch(r -> matches(offer, r)));
		for (MaterialRequest request : open) {
			if (findOffer(offers, request) == null) {
				offers.add(new MerchantOffer(new ItemCost(request.item(), request.count()),
						new ItemStack(Items.EMERALD, request.reward()), 1, TRADE_XP, 0.0F));
			}
		}
	}

	private static void removeOffers(Villager villager, Predicate<MerchantOffer> filter) {
		villager.getOffers().removeIf(offer -> isBoardOffer(offer) && filter.test(offer));
	}

	/** Board offers: one use, fixed price (multiplier 0, never used by vanilla trades), emeralds as the result. */
	private static boolean isBoardOffer(MerchantOffer offer) {
		return offer.getMaxUses() == 1 && offer.getPriceMultiplier() == 0.0F && offer.getResult().is(Items.EMERALD);
	}

	private static boolean matches(MerchantOffer offer, MaterialRequest request) {
		ItemCost cost = offer.getItemCostA();
		return cost.item().value() == request.item() && cost.count() == request.count()
				&& offer.getResult().getCount() == request.reward();
	}

	@Nullable
	private static MerchantOffer findOffer(MerchantOffers offers, MaterialRequest request) {
		for (MerchantOffer offer : offers) {
			if (isBoardOffer(offer) && matches(offer, request)) {
				return offer;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- manage interval

	/**
	 * At the manage interval: chooses the next building ahead of time, keeps the requests in line with the
	 * building being built or planned, and updates the board sign. {@code stats} is null when needs are off.
	 */
	public static void manage(ServerLevel level, VillageRecord village, @Nullable VillageAnalyzer.Stats stats) {
		if (!LVConfig.get().boardEnabled) {
			return;
		}
		BuildProject project = village.getProject();
		ResourceLocation target = project != null ? project.getTemplateId() : plan(level, village, stats);
		refreshRequests(level, village, target);
		if (!village.isBoardPlaced()) {
			placeBoard(level, village);
		}
		updateSign(level, village);
	}

	/** The building the leader will build next (chosen once, kept until the need changes), or null. */
	@Nullable
	private static ResourceLocation plan(ServerLevel level, VillageRecord village, @Nullable VillageAnalyzer.Stats stats) {
		if (stats == null || village.getNeeds() == null) {
			return null;
		}
		List<BuildingTemplate> buildings = BuildingTemplateProvider.getBuildings(level, VillageTicker.villageType(village));
		BuildDecision decision = BuildDecision.decide(level, village, stats, village.getNeeds(), buildings);
		if (!decision.builds()) {
			village.setPlannedTemplate(null);
			return null;
		}
		List<BuildingTemplate> candidates = decision.candidates(buildings);
		ResourceLocation planned = village.getPlannedTemplate();
		if (planned == null || candidates.stream().noneMatch(b -> b.id().equals(planned))) {
			BuildingTemplate pick = BuildingTemplateProvider.pickRandom(candidates, level.getRandom());
			village.setPlannedTemplate(pick == null ? null : pick.id());
			VillageRegistry.get(level).setDirty();
		}
		return village.getPlannedTemplate();
	}

	private static void refreshRequests(ServerLevel level, VillageRecord village, @Nullable ResourceLocation target) {
		long day = level.getDayTime() / 24000L;
		List<MaterialRequest> requests = village.getRequests();
		boolean allDone = !requests.isEmpty() && requests.stream().allMatch(MaterialRequest::isFulfilled);
		boolean expired = day - village.getRequestDay() >= LVConfig.get().requestExpireDays;
		if (target != null && target.equals(village.getRequestTemplate()) && (!expired || allDone)) {
			return;
		}
		if (target != null && target.equals(village.getRequestTemplate()) && village.getProject() == null) {
			// Expired while waiting: plan another building of the same kind, whose materials may differ.
			village.setPlannedTemplate(null);
		}
		List<MaterialRequest> fresh = target == null ? List.of() : requestsFor(level, village, target);
		// The same request made again keeps its delivered state, so it is never paid twice.
		for (MaterialRequest request : fresh) {
			for (MaterialRequest old : requests) {
				if (old.sameAs(request) && old.isFulfilled() && target.equals(village.getRequestTemplate())) {
					request.setFulfilled(true);
				}
			}
		}
		if (requests.isEmpty() && fresh.isEmpty() && village.getRequestTemplate() == null) {
			return;
		}
		requests.clear();
		requests.addAll(fresh);
		village.setRequestTemplate(target, day);
		VillageRegistry.get(level).setDirty();
		LivingVillages.debug("Village {}: board requests for {}: {}", village.getId(), target,
				fresh.stream().map(r -> r.count() + " " + r.itemId().getPath()).toList());
	}

	/** The maxRequests materials the template uses most, rounded up to a multiple of 8 within 8–64. */
	private static List<MaterialRequest> requestsFor(ServerLevel level, VillageRecord village, ResourceLocation templateId) {
		BuildingTemplate building = BuildingTemplateProvider.findById(level, VillageTicker.villageType(village), templateId);
		if (building == null) {
			return List.of();
		}
		Map<Item, Integer> counts = new HashMap<>();
		for (StructureTemplate.StructureBlockInfo info : building.blocks()) {
			BlockState state = info.state();
			if (isTrivial(state)) {
				continue;
			}
			Item item = state.getBlock().asItem();
			if (item == Items.AIR || item.getDefaultMaxStackSize() < MAX_COUNT) {
				continue; // beds, signs... cannot be asked for by the dozen
			}
			counts.merge(item, 1, Integer::sum);
		}
		LVConfig config = LVConfig.get();
		List<MaterialRequest> requests = new ArrayList<>();
		counts.entrySet().stream()
				.sorted(Comparator.comparingInt((Map.Entry<Item, Integer> e) -> -e.getValue())
						.thenComparing(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
				.limit(config.maxRequests)
				.forEach(e -> {
					int count = Mth.clamp(Mth.roundToward(e.getValue(), MIN_COUNT), MIN_COUNT, MAX_COUNT);
					int perEmerald = config.itemsPerEmerald.getOrDefault(group(e.getKey()), config.itemsPerEmerald.get("default"));
					requests.add(new MaterialRequest(BuiltInRegistries.ITEM.getKey(e.getKey()), count, Mth.positiveCeilDiv(count, perEmerald)));
				});
		return requests;
	}

	/** Earth, sand, plants and the second half of two-block blocks are not asked for. */
	private static boolean isTrivial(BlockState state) {
		if (state.isAir() || state.canBeReplaced() || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)
				|| state.is(BlockTags.FLOWERS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.CROPS)
				|| state.is(BlockTags.SAPLINGS)) {
			return true;
		}
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return true;
		}
		return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
	}

	/** Reward group of an item: wood, stone, glass, wool or default. */
	private static String group(Item item) {
		ItemStack stack = new ItemStack(item);
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.WOODEN_SLABS) || stack.is(ItemTags.WOODEN_STAIRS)
				|| stack.is(ItemTags.WOODEN_FENCES) || stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOODEN_TRAPDOORS)
				|| stack.is(ItemTags.FENCE_GATES)) {
			return "wood";
		}
		if (path.contains("glass")) {
			return "glass";
		}
		if (stack.is(ItemTags.WOOL) || stack.is(ItemTags.WOOL_CARPETS)) {
			return "wool";
		}
		for (String stone : List.of("stone", "cobble", "brick", "terracotta", "andesite", "diorite", "granite", "deepslate", "tuff")) {
			if (path.contains(stone)) {
				return "stone";
			}
		}
		return "default";
	}

	// ---------------------------------------------------------------- the board sign

	/** Places the board once, on a free spot with firm ground 2–4 blocks from the bell, facing the bell. */
	public static boolean placeBoard(ServerLevel level, VillageRecord village) {
		BlockPos bell = village.getBellPos();
		for (int radius = BOARD_MIN_RADIUS; radius <= BOARD_MAX_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos spot = boardSpot(level, village, bell.getX() + dx, bell.getZ() + dz);
					if (spot != null) {
						float yawToBell = (float) (Mth.atan2(-(bell.getX() - spot.getX()), bell.getZ() - spot.getZ()) * Mth.RAD_TO_DEG);
						BlockState sign = Graveyard.signBlock(village).defaultBlockState()
								.setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(yawToBell));
						level.setBlockAndUpdate(spot, sign);
						village.setBoardPos(spot);
						village.setBoardPlaced(true);
						SIGN_TEXT.remove(village.getId());
						VillageRegistry.get(level).setDirty();
						LivingVillages.debug("Village {}: board placed at {}", village.getId(), spot);
						return true;
					}
				}
			}
		}
		return false;
	}

	/** A free block above firm ground that is not a path, a plot or a job site. */
	@Nullable
	private static BlockPos boardSpot(ServerLevel level, VillageRecord village, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		BlockPos spot = new BlockPos(x, SiteFinder.groundTop(level, x, z) + 1, z);
		if (Math.abs(spot.getY() - village.getBellPos().getY()) > BOARD_VERTICAL_RANGE) {
			return null;
		}
		BlockState below = level.getBlockState(spot.below());
		if (!level.getBlockState(spot).isAir() || !level.getBlockState(spot.above()).isAir()
				|| !below.isFaceSturdy(level, spot.below(), Direction.UP) || below.is(Blocks.DIRT_PATH) || below.is(BlockTags.LEAVES)) {
			return null;
		}
		for (BoundingBox plot : village.getPlots()) {
			if (plot.isInside(spot)) {
				return null;
			}
		}
		return level.getPoiManager().existsAtPosition(PoiTypes.MEETING, spot) ? null : spot;
	}

	/** "THE VILLAGE NEEDS" and one line per open request, or "The village has all it needs". */
	private static void updateSign(ServerLevel level, VillageRecord village) {
		BlockPos pos = village.getBoardPos();
		if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)
				|| !level.getBlockState(pos).is(BlockTags.STANDING_SIGNS)) {
			return; // broken by a player: not placed again on its own
		}
		List<MaterialRequest> open = village.getRequests().stream().filter(r -> !r.isFulfilled()).toList();
		String key = LVConfig.get().language + open.stream().map(r -> r.count() + r.itemId().toString()).toList();
		if (key.equals(SIGN_TEXT.get(village.getId()))) {
			return;
		}
		SIGN_TEXT.put(village.getId(), key);
		SignText text = new SignText().setMessage(0, LVText.tr("livingvillages.board.title"));
		if (open.isEmpty()) {
			text = text.setMessage(1, LVText.tr("livingvillages.board.enough"));
		}
		for (int i = 0; i < Math.min(3, open.size()); i++) {
			text = text.setMessage(i + 1, requestLine(open.get(i)));
		}
		sign.setText(text, true);
		sign.setText(text, false);
		sign.setWaxed(true);
	}

	/** "32 Glass": the item name is vanilla's, so every player reads it in their own language. */
	public static Component requestLine(MaterialRequest request) {
		return Component.literal(request.count() + " ").append(Component.translatable(request.item().getDescriptionId()));
	}

	/** Forgets per-session state (server stop). */
	public static void clear() {
		TRADERS.clear();
		SIGN_TEXT.clear();
	}
}
