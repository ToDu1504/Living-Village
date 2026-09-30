package com.khanhvu.livingvillages.festival;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Village festival (spec v2-GĐ 8): every festivalIntervalDays days at dusk, when the village is not miserable, not
 * raided and it does not rain. Villagers gather around the bell, the bell sounds, fireworks go up around the square
 * and villagers say festival lines. No block is placed. A festival that cannot be held waits for the next dusk.
 * <p>
 * The bell itself is not rung: a ringing bell makes villagers hide indoors (vanilla HEARD_BELL_TIME), so only its
 * sound is played.
 */
public final class Festival {
	/** Dusk window in which a festival may start (time of day). */
	private static final long START_TIME = 11000;
	private static final long START_WINDOW_END = 12000;
	private static final int GATHER_INTERVAL = 40;
	private static final int BELL_INTERVAL = 100;
	private static final int FIREWORK_INTERVAL = 15;
	private static final int GATHER_MIN_RADIUS = 3;
	private static final int GATHER_MAX_RADIUS = 6;
	private static final int FIREWORK_MIN_RADIUS = 6;
	private static final int FIREWORK_MAX_RADIUS = 12;
	/** Fireworks start at least this far from any creature, and explode high above it. */
	private static final double FIREWORK_CLEARANCE = 4.0;
	private static final int VERTICAL_RANGE = 16;
	/** Four festival names through a year of 96 days. */
	private static final int SEASON_DAYS = 24;
	private static final String[] SEASONS = {"spring", "summer", "harvest", "winter"};

	/** Game tick at which each running festival ends. Not saved: a restart ends a festival. */
	private static final Map<UUID, Long> END = new HashMap<>();

	private Festival() {
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		LVConfig config = LVConfig.get();
		long now = level.getGameTime();
		Long end = END.get(village.getId());
		if (end != null) {
			if (!config.festivalEnabled || now >= end) {
				stop(village);
			} else {
				run(level, village, now);
			}
			return;
		}
		if (!config.festivalEnabled || now % 20 != 0) {
			return;
		}
		long day = level.getDayTime() / 24000L;
		long timeOfDay = level.getDayTime() % 24000L;
		if (village.getLastFestivalDay() == VillageRecord.NEVER) {
			village.setLastFestivalDay(day); // count from the day the village is first seen
			VillageRegistry.get(level).setDirty();
			return;
		}
		if (day - village.getLastFestivalDay() < config.festivalIntervalDays || timeOfDay < START_TIME || timeOfDay >= START_WINDOW_END) {
			return;
		}
		if (canHold(level, village)) {
			start(level, village);
		}
		// Otherwise it is postponed: tomorrow's dusk is checked again.
	}

	private static boolean canHold(ServerLevel level, VillageRecord village) {
		boolean miserable = village.getMood() >= 0 && VillageMood.Level.of(village.getMood()) == VillageMood.Level.MISERABLE;
		// The weather itself: level.isRaining() only turns true once the rain has faded in.
		boolean rain = level.getLevelData().isRaining() || level.getLevelData().isThundering();
		return !miserable && !rain && level.getRaidAt(village.getBellPos()) == null;
	}

	/** Starts a festival now (also used by the festival command). */
	public static void start(ServerLevel level, VillageRecord village) {
		long now = level.getGameTime();
		END.put(village.getId(), now + LVConfig.get().festivalDurationTicks);
		village.setFestivalActive(true);
		long day = level.getDayTime() / 24000L;
		village.setLastFestivalDay(day);
		VillageRegistry.get(level).setDirty();
		String nameKey = "livingvillages.festival." + SEASONS[(int) (Math.floorMod(day, SEASON_DAYS * SEASONS.length) / SEASON_DAYS)];
		LivingVillages.debug("Village {} starts a festival ({})", village.getId(), nameKey);
		VillageEvents.FESTIVAL.post(new VillageEvents.FestivalStarted(level, village, nameKey));
	}

	public static boolean isRunning(VillageRecord village) {
		return END.containsKey(village.getId());
	}

	private static void stop(VillageRecord village) {
		END.remove(village.getId());
		village.setFestivalActive(false);
		LivingVillages.debug("Village {} festival is over", village.getId());
	}

	private static void run(ServerLevel level, VillageRecord village, long now) {
		BlockPos bell = village.getBellPos();
		RandomSource random = level.getRandom();
		if (now % GATHER_INTERVAL == 0) {
			BuildProject project = village.getProject();
			UUID builder = project == null ? null : project.getBuilderUuid();
			int radius = VillageAnalyzer.areaRadius(village);
			for (Villager villager : VillageAnalyzer.getAdultVillagers(level, bell, radius)) {
				if (!villager.isSleeping() && !villager.isTrading() && !villager.getUUID().equals(builder)) {
					BuilderAssignment.walkTo(villager, around(bell, GATHER_MIN_RADIUS, GATHER_MAX_RADIUS, random), bell);
				}
			}
		}
		if (now % BELL_INTERVAL == 0) {
			level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
		if (now % FIREWORK_INTERVAL == 0 && random.nextBoolean()) {
			launchFirework(level, bell, random);
		}
	}

	private static BlockPos around(BlockPos center, int minRadius, int maxRadius, RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2.0;
		double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
		return center.offset(Mth.floor(Math.cos(angle) * radius), 0, Mth.floor(Math.sin(angle) * radius));
	}

	/** A rocket from open ground at least FIREWORK_CLEARANCE from any creature; it explodes far above the square. */
	private static void launchFirework(ServerLevel level, BlockPos bell, RandomSource random) {
		BlockPos spot = around(bell, FIREWORK_MIN_RADIUS, FIREWORK_MAX_RADIUS, random);
		if (!level.hasChunk(spot.getX() >> 4, spot.getZ() >> 4)) {
			return;
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot.getX(), spot.getZ());
		if (Math.abs(y - bell.getY()) > VERTICAL_RANGE) {
			return;
		}
		double x = spot.getX() + 0.5;
		double z = spot.getZ() + 0.5;
		AABB clearance = new AABB(x, y, z, x, y, z).inflate(FIREWORK_CLEARANCE);
		if (!level.getEntitiesOfClass(LivingEntity.class, clearance).isEmpty()) {
			return;
		}
		ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
		FireworkExplosion.Shape[] shapes = {FireworkExplosion.Shape.SMALL_BALL, FireworkExplosion.Shape.LARGE_BALL,
				FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST};
		DyeColor[] colors = DyeColor.values();
		FireworkExplosion explosion = new FireworkExplosion(shapes[random.nextInt(shapes.length)],
				IntList.of(colors[random.nextInt(colors.length)].getFireworkColor(), colors[random.nextInt(colors.length)].getFireworkColor()),
				IntList.of(colors[random.nextInt(colors.length)].getFireworkColor()), random.nextBoolean(), random.nextBoolean());
		rocket.set(DataComponents.FIREWORKS, new Fireworks(2 + random.nextInt(2), List.of(explosion)));
		level.addFreshEntity(new FireworkRocketEntity(level, x, y + 0.5, z, rocket));
	}

	/** Ends every festival (server stop). */
	public static void clear() {
		END.clear();
	}
}
