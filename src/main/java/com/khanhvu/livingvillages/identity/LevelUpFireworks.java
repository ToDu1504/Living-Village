package com.khanhvu.livingvillages.identity;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
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
 * Fireworks over the village square when a village reaches a new level: a short show of vanilla rockets launched
 * one after another from open ground around the bell. Rockets start at least {@link #CLEARANCE} blocks from any
 * creature and explode high up, so nobody is hurt and nothing burns. No block is placed.
 */
public final class LevelUpFireworks {
	private static final int ROCKETS = 16;
	private static final int INTERVAL_TICKS = 8;
	/** A spot that is not free is tried again this many times before the rocket is skipped. */
	private static final int SPOT_ATTEMPTS = 4;
	private static final int MIN_RADIUS = 6;
	private static final int MAX_RADIUS = 12;
	private static final double CLEARANCE = 4.0;
	private static final int VERTICAL_RANGE = 16;
	private static final FireworkExplosion.Shape[] SHAPES = {FireworkExplosion.Shape.SMALL_BALL, FireworkExplosion.Shape.LARGE_BALL,
			FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST};

	/** Rockets still to launch per village. Not saved: a restart ends the show. */
	private static final Map<UUID, Integer> SHOWS = new HashMap<>();

	private LevelUpFireworks() {
	}

	public static void register() {
		VillageEvents.LEVEL_UP.register(e -> {
			if (LVConfig.get().levelUpFireworks) {
				SHOWS.put(e.village().getId(), ROCKETS);
				LivingVillages.debug("Village {}: level-up fireworks", e.village().getId());
			}
		});
	}

	/** Called every tick for villages near a player. */
	public static void tick(ServerLevel level, VillageRecord village) {
		Integer left = SHOWS.get(village.getId());
		if (left == null || level.getGameTime() % INTERVAL_TICKS != 0) {
			return;
		}
		launch(level, village.getBellPos(), level.getRandom());
		if (left <= 1) {
			SHOWS.remove(village.getId());
		} else {
			SHOWS.put(village.getId(), left - 1);
		}
	}

	/** One rocket from open ground away from everyone; it explodes far above the square. */
	private static void launch(ServerLevel level, BlockPos bell, RandomSource random) {
		for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double radius = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
			int sx = bell.getX() + Mth.floor(Math.cos(angle) * radius);
			int sz = bell.getZ() + Mth.floor(Math.sin(angle) * radius);
			if (!level.hasChunk(sx >> 4, sz >> 4)) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, sx, sz);
			if (Math.abs(y - bell.getY()) > VERTICAL_RANGE) {
				continue;
			}
			double x = sx + 0.5;
			double z = sz + 0.5;
			if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(x, y, z, x, y, z).inflate(CLEARANCE)).isEmpty()) {
				continue;
			}
			DyeColor[] colors = DyeColor.values();
			FireworkExplosion explosion = new FireworkExplosion(SHAPES[random.nextInt(SHAPES.length)],
					IntList.of(colors[random.nextInt(colors.length)].getFireworkColor(), colors[random.nextInt(colors.length)].getFireworkColor()),
					IntList.of(colors[random.nextInt(colors.length)].getFireworkColor()), random.nextBoolean(), random.nextBoolean());
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			rocket.set(DataComponents.FIREWORKS, new Fireworks(2 + random.nextInt(2), List.of(explosion)));
			level.addFreshEntity(new FireworkRocketEntity(level, x, y + 0.5, z, rocket));
			return;
		}
	}

	/** Ends every show (server stop). */
	public static void clear() {
		SHOWS.clear();
	}
}
