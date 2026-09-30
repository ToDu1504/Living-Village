package com.khanhvu.livingvillages.work;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.VillageLevel;
import com.khanhvu.livingvillages.mixin.ZombieVillagerInvoker;
import com.khanhvu.livingvillages.society.VillageMood;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageEvents;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import com.khanhvu.livingvillages.worker.BuilderAssignment;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every profession has a visible job with a real effect (spec v2-GĐ 4). Every {@code workIntervalTicks} each working
 * villager may pick one task; it walks there and the task happens on arrival (within {@link #ARRIVE_DISTANCE}), or is
 * dropped after {@code workTimeoutTicks}. Only villages near a player run tasks.
 */
public final class ProfessionWork {
	private static final double ARRIVE_DISTANCE = 3.0;
	private static final int VERTICAL_RANGE = 24;
	private static final int FISH_SEARCH_ATTEMPTS = 24;
	private static final int FISH_SEARCH_RADIUS = 20;
	private static final int MAX_TRACKED_CURES = 256;

	private enum Kind { BREED, SHEAR, SMOKER, CAULDRON, FISH, HEAL, CURE, SMITH, SURVEY }

	/**
	 * A task in progress. {@code target} is where to walk; {@code subject} is the entity worked on (animal,
	 * patient), if any; {@code partner} the second animal when breeding.
	 */
	private record Task(ResourceKey<Level> dimension, Kind kind, BlockPos target, @Nullable UUID subject, @Nullable UUID partner, long startTick, boolean heldItem) {
	}

	/** Tasks per villager. Not saved: after a restart villagers simply pick new tasks. */
	private static final Map<UUID, Task> TASKS = new HashMap<>();
	/** Game day of each cleric's last cure (at most clericCuresPerDay per day). */
	private static final Map<UUID, Long> CURE_DAY = new HashMap<>();
	private static final Map<UUID, Integer> CURES_TODAY = new HashMap<>();
	/** Zombie villagers a cleric started curing → that cleric. Not saved: a cure finished after a restart is not credited. */
	private static final Map<UUID, UUID> CURING = new HashMap<>();

	private ProfessionWork() {
	}

	public static void register() {
		// A cure started by a cleric ends in a vanilla conversion: zombie villager → villager.
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, keepEquipment) -> {
			UUID clericId = CURING.remove(previous.getUUID());
			if (clericId == null || !(previous instanceof ZombieVillager) || !(converted instanceof Villager villager)
					|| !(converted.level() instanceof ServerLevel level)) {
				return;
			}
			VillageRecord village = VillageRegistry.get(level).findContaining(villager.blockPosition());
			if (village != null) {
				Villager cleric = level.getEntity(clericId) instanceof Villager v ? v : null;
				VillageEvents.ZOMBIE_CURED.post(new VillageEvents.ZombieCured(level, village, cleric, villager));
			}
		});
	}

	/** Called every tick for villages near a player: villagers pick new tasks every workIntervalTicks. */
	public static void tick(ServerLevel level, VillageRecord village) {
		long now = level.getGameTime();
		// Stagger villages so they do not all pick tasks on the same tick.
		if (Math.floorMod(now + village.getId().hashCode(), LVConfig.get().workIntervalTicks) == 0) {
			pickTasks(level, village, now);
		}
	}

	/** Called once per level tick: moves villagers toward their tasks and carries them out on arrival. */
	public static void tickTasks(ServerLevel level) {
		if (!TASKS.isEmpty()) {
			runTasks(level, level.getGameTime());
		}
	}

	// ---------------------------------------------------------------- choosing

	private static void pickTasks(ServerLevel level, VillageRecord village, long now) {
		if (village.isFestivalActive()) {
			return; // everyone is at the festival
		}
		LVConfig config = LVConfig.get();
		int radius = VillageAnalyzer.areaRadius(village);
		List<Villager> adults = VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), radius);
		double chance = config.workChance * moodFactor(village);
		BuildProject project = village.getProject();
		UUID builder = project == null ? null : project.getBuilderUuid();
		for (Villager villager : adults) {
			if (TASKS.containsKey(villager.getUUID()) || villager.getUUID().equals(builder) || villager.isTrading()
					|| villager.isSleeping() || !villager.getBrain().isActive(Activity.WORK)) {
				continue;
			}
			VillagerProfession profession = villager.getVillagerData().getProfession();
			if (!isEnabled(profession) || level.getRandom().nextDouble() >= chance) {
				continue;
			}
			Task task = chooseTask(level, village, villager, profession, radius, now);
			if (task != null) {
				TASKS.put(villager.getUUID(), task);
				if (task.heldItem()) {
					hold(villager, heldItemFor(task.kind()));
				}
			}
		}
	}

	@Nullable
	private static Task chooseTask(ServerLevel level, VillageRecord village, Villager villager, VillagerProfession profession,
			int radius, long now) {
		AABB area = new AABB(village.getBellPos()).inflate(radius, VERTICAL_RANGE, radius);
		RandomSource random = level.getRandom();
		if (profession == VillagerProfession.SHEPHERD) {
			Sheep shearable = level.getEntitiesOfClass(Sheep.class, area, s -> s.readyForShearing() && workable(s)).stream()
					.findFirst().orElse(null);
			if (shearable != null) {
				return new Task(level.dimension(), Kind.SHEAR, shearable.blockPosition(), shearable.getUUID(), null, now, true);
			}
			return breedTask(level, village, area, EntityType.SHEEP, now);
		}
		if (profession == VillagerProfession.BUTCHER) {
			BlockPos jobSite = jobSite(level, villager);
			if (jobSite != null && random.nextBoolean() && level.getBlockEntity(jobSite) instanceof AbstractFurnaceBlockEntity) {
				return new Task(level.dimension(), Kind.SMOKER, jobSite, null, null, now, false);
			}
			return breedTask(level, village, area, EntityType.PIG, now);
		}
		if (profession == VillagerProfession.LEATHERWORKER) {
			BlockPos jobSite = jobSite(level, villager);
			if (jobSite != null && level.getBlockState(jobSite).is(Blocks.CAULDRON)) {
				return new Task(level.dimension(), Kind.CAULDRON, jobSite, null, null, now, false);
			}
			return breedTask(level, village, area, EntityType.COW, now);
		}
		if (profession == VillagerProfession.FLETCHER) {
			return breedTask(level, village, area, EntityType.CHICKEN, now);
		}
		if (profession == VillagerProfession.FISHERMAN) {
			BlockPos jobSite = jobSite(level, villager);
			BlockPos shore = jobSite == null ? null : findShore(level, jobSite, random);
			return shore == null ? null : new Task(level.dimension(), Kind.FISH, shore, null, null, now, true);
		}
		if (profession == VillagerProfession.CLERIC) {
			return clericTask(level, village, villager, area, now);
		}
		if (profession == VillagerProfession.ARMORER || profession == VillagerProfession.WEAPONSMITH
				|| profession == VillagerProfession.TOOLSMITH) {
			BlockPos jobSite = jobSite(level, villager);
			return jobSite == null ? null : new Task(level.dimension(), Kind.SMITH, jobSite, null, null, now, false);
		}
		if (profession == VillagerProfession.CARTOGRAPHER) {
			long timeOfDay = level.getDayTime() % 24000L;
			if (timeOfDay < 3000) { // early morning walk around the village edge
				int edge = VillageLevel.buildRadius(village);
				double angle = random.nextDouble() * Math.PI * 2;
				int x = village.getBellPos().getX() + Mth.floor(Math.cos(angle) * edge);
				int z = village.getBellPos().getZ() + Mth.floor(Math.sin(angle) * edge);
				if (level.hasChunk(x >> 4, z >> 4)) {
					BlockPos target = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
					return new Task(level.dimension(), Kind.SURVEY, target, null, null, now, true);
				}
			}
		}
		return null;
	}

	/** Two adult animals of {@code type} ready to breed, below the village's cap for that animal. */
	@Nullable
	private static Task breedTask(ServerLevel level, VillageRecord village, AABB area, EntityType<? extends Animal> type, long now) {
		List<Animal> all = level.getEntitiesOfClass(Animal.class, area, a -> a.getType() == type && a.isAlive());
		int cap = LVConfig.get().maxAnimalsPerType.get(Math.max(0, Math.min(VillageLevel.MAX, village.getLevel())));
		if (all.size() >= cap) {
			return null;
		}
		List<Animal> ready = all.stream().filter(a -> workable(a) && !a.isBaby() && a.getAge() == 0 && a.canFallInLove()).toList();
		if (ready.size() < 2) {
			return null;
		}
		Animal first = ready.get(0);
		Animal second = ready.stream().skip(1).min((a, b) -> Double.compare(a.distanceToSqr(first), b.distanceToSqr(first))).orElseThrow();
		return new Task(level.dimension(), Kind.BREED, first.blockPosition(), first.getUUID(), second.getUUID(), now, true);
	}

	@Nullable
	private static Task clericTask(ServerLevel level, VillageRecord village, Villager cleric, AABB area, long now) {
		LVConfig config = LVConfig.get();
		if (config.clericCureZombies && curesLeftToday(cleric, level) > 0) {
			ZombieVillager zombie = level.getEntitiesOfClass(ZombieVillager.class, area, z -> z.isAlive() && !z.isConverting()
					&& !z.hasCustomName() && !z.isLeashed() && !z.isPersistenceRequired()).stream().findFirst().orElse(null);
			if (zombie != null) {
				return new Task(level.dimension(), Kind.CURE, zombie.blockPosition(), zombie.getUUID(), null, now, false);
			}
		}
		Villager patient = level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && v.getHealth() < v.getMaxHealth()
				&& v != cleric).stream().findFirst().orElse(null);
		return patient == null ? null : new Task(level.dimension(), Kind.HEAL, patient.blockPosition(), patient.getUUID(), null, now, false);
	}

	// ---------------------------------------------------------------- running

	private static void runTasks(ServerLevel level, long now) {
		int timeout = LVConfig.get().workTimeoutTicks;
		for (Iterator<Map.Entry<UUID, Task>> it = TASKS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Task> entry = it.next();
			Task task = entry.getValue();
			if (task.dimension() != level.dimension()) {
				continue;
			}
			Entity entity = level.getEntity(entry.getKey());
			if (!(entity instanceof Villager villager) || !villager.isAlive()) {
				if (entity != null || now - task.startTick() > timeout) {
					it.remove(); // gone, or unloaded for too long
				}
				continue;
			}
			BlockPos target = currentTarget(level, task);
			if (target == null || now - task.startTick() > timeout) {
				finish(villager, task);
				it.remove();
				continue;
			}
			double reach = task.kind() == Kind.CURE ? LVConfig.get().clericCureRange + 1 : ARRIVE_DISTANCE;
			if (villager.position().closerThan(target.getCenter(), reach)) {
				perform(level, villager, task, now);
				finish(villager, task);
				it.remove();
			} else {
				BuilderAssignment.walkTo(villager, walkTarget(task, target, villager), target);
			}
		}
	}

	/** Moving subjects (animals, patients) are followed; places stay put. Null when the subject is gone. */
	@Nullable
	private static BlockPos currentTarget(ServerLevel level, Task task) {
		if (task.subject() == null) {
			return task.target();
		}
		Entity subject = level.getEntity(task.subject());
		return subject != null && subject.isAlive() ? subject.blockPosition() : null;
	}

	/** The cleric stops a few blocks away from a zombie villager instead of walking into it. */
	private static BlockPos walkTarget(Task task, BlockPos target, Villager villager) {
		if (task.kind() != Kind.CURE) {
			return target;
		}
		double range = LVConfig.get().clericCureRange;
		var away = villager.position().subtract(target.getCenter()).normalize().scale(range);
		return BlockPos.containing(target.getCenter().add(away));
	}

	private static void perform(ServerLevel level, Villager villager, Task task, long now) {
		villager.swing(InteractionHand.MAIN_HAND);
		LivingVillages.debug("{} ({}) does {} at {}", villager.getName().getString(),
				BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).getPath(), task.kind(), task.target());
		switch (task.kind()) {
			case BREED -> {
				if (level.getEntity(task.subject()) instanceof Animal first && level.getEntity(task.partner()) instanceof Animal second
						&& first.canFallInLove() && second.canFallInLove()) {
					first.setInLove(null); // vanilla shows the hearts and does the breeding
					second.setInLove(null);
				}
			}
			case SHEAR -> {
				if (level.getEntity(task.subject()) instanceof Sheep sheep && sheep.readyForShearing()) {
					sheep.shear(SoundSource.NEUTRAL);
				}
			}
			case SMOKER -> feedSmoker(level, task.target());
			case CAULDRON -> {
				if (level.getBlockState(task.target()).is(Blocks.CAULDRON)) {
					level.setBlockAndUpdate(task.target(), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
					effect(level, task.target(), ParticleTypes.SPLASH, SoundEvents.BUCKET_EMPTY);
				}
			}
			case FISH -> {
				effect(level, task.target(), ParticleTypes.FISHING, SoundEvents.FISHING_BOBBER_SPLASH);
				BlockPos barrel = jobSite(level, villager);
				if (barrel != null) {
					storeFish(level, barrel, level.getRandom().nextBoolean() ? Items.COD : Items.SALMON);
				}
			}
			case HEAL -> {
				if (level.getEntity(task.subject()) instanceof Villager patient) {
					patient.heal(4.0F);
					effect(level, patient.blockPosition().above(), ParticleTypes.HEART, SoundEvents.VILLAGER_WORK_CLERIC);
				}
			}
			case CURE -> {
				if (level.getEntity(task.subject()) instanceof ZombieVillager zombie && !zombie.isConverting()) {
					((ZombieVillagerInvoker) zombie).livingvillages$startConverting(null, 3600 + level.getRandom().nextInt(2401));
					effect(level, zombie.blockPosition().above(), ParticleTypes.HAPPY_VILLAGER, SoundEvents.VILLAGER_WORK_CLERIC);
					countCure(villager, level);
					if (CURING.size() >= MAX_TRACKED_CURES) {
						CURING.clear(); // zombies that died before the cure ended are never removed otherwise
					}
					CURING.put(zombie.getUUID(), villager.getUUID());
					LivingVillages.debug("Cleric {} started curing zombie villager {}", villager.getUUID(), zombie.getUUID());
				}
			}
			case SMITH -> effect(level, task.target().above(), ParticleTypes.LAVA, SoundEvents.ANVIL_USE);
			case SURVEY -> effect(level, villager.blockPosition().above(), ParticleTypes.HAPPY_VILLAGER, SoundEvents.VILLAGER_WORK_CLERIC);
		}
	}

	private static void finish(Villager villager, Task task) {
		if (task.heldItem()) {
			hold(villager, Items.AIR);
		}
	}

	/** One raw meat and one charcoal into the butcher's own smoker, only when its input is empty and the output not full. */
	private static void feedSmoker(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity smoker)) {
			return;
		}
		if (!smoker.getItem(0).isEmpty() || smoker.getItem(2).getCount() >= 16) {
			return;
		}
		Item meat = switch (level.getRandom().nextInt(3)) {
			case 0 -> Items.PORKCHOP;
			case 1 -> Items.BEEF;
			default -> Items.CHICKEN;
		};
		smoker.setItem(0, new ItemStack(meat));
		if (smoker.getItem(1).isEmpty()) {
			smoker.setItem(1, new ItemStack(Items.CHARCOAL));
		}
		smoker.setChanged();
		effect(level, pos.above(), ParticleTypes.SMOKE, SoundEvents.VILLAGER_WORK_CLERIC);
	}

	/** Puts one fish into the fisherman's own barrel, up to maxFishInBarrel fish in total. */
	private static void storeFish(ServerLevel level, BlockPos pos, Item fish) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof Container barrel)) {
			return;
		}
		int fishCount = 0;
		for (int i = 0; i < barrel.getContainerSize(); i++) {
			ItemStack stack = barrel.getItem(i);
			if (stack.is(Items.COD) || stack.is(Items.SALMON)) {
				fishCount += stack.getCount();
			}
		}
		if (fishCount >= LVConfig.get().maxFishInBarrel) {
			return;
		}
		for (int i = 0; i < barrel.getContainerSize(); i++) {
			ItemStack stack = barrel.getItem(i);
			if (stack.is(fish) && stack.getCount() < stack.getMaxStackSize()) {
				stack.grow(1);
				barrel.setChanged();
				return;
			}
		}
		for (int i = 0; i < barrel.getContainerSize(); i++) {
			if (barrel.getItem(i).isEmpty()) {
				barrel.setItem(i, new ItemStack(fish));
				barrel.setChanged();
				return;
			}
		}
	}

	/** A water surface block within reach of the fisherman's job site, and the dry block next to it to stand on. */
	@Nullable
	private static BlockPos findShore(ServerLevel level, BlockPos jobSite, RandomSource random) {
		for (int i = 0; i < FISH_SEARCH_ATTEMPTS; i++) {
			int x = jobSite.getX() + random.nextInt(FISH_SEARCH_RADIUS * 2 + 1) - FISH_SEARCH_RADIUS;
			int z = jobSite.getZ() + random.nextInt(FISH_SEARCH_RADIUS * 2 + 1) - FISH_SEARCH_RADIUS;
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			BlockPos surface = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
			if (level.getFluidState(surface).isSource() && level.getBlockState(surface).is(Blocks.WATER)) {
				return surface;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- helpers

	@Nullable
	private static BlockPos jobSite(ServerLevel level, Villager villager) {
		return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
				.filter(global -> global.dimension() == level.dimension())
				.map(GlobalPos::pos)
				.filter(level::isLoaded)
				.orElse(null);
	}

	/** Animals named by a player or on a lead are left alone. */
	private static boolean workable(Animal animal) {
		return animal.isAlive() && !animal.hasCustomName() && !animal.isLeashed();
	}

	private static boolean isEnabled(VillagerProfession profession) {
		String id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath();
		return LVConfig.get().professionWork.getOrDefault(id, false);
	}

	/** Happy villagers work more often (×1.25), miserable ones less (×0.75). */
	private static double moodFactor(VillageRecord village) {
		if (village.getMood() < 0) {
			return 1.0;
		}
		return switch (VillageMood.Level.of(village.getMood())) {
			case HAPPY -> 1.25;
			case MISERABLE -> 0.75;
			case NORMAL -> 1.0;
		};
	}

	private static int curesLeftToday(Villager cleric, ServerLevel level) {
		long day = level.getDayTime() / 24000L;
		if (CURE_DAY.getOrDefault(cleric.getUUID(), -1L) != day) {
			return LVConfig.get().clericCuresPerDay;
		}
		return LVConfig.get().clericCuresPerDay - CURES_TODAY.getOrDefault(cleric.getUUID(), 0);
	}

	private static void countCure(Villager cleric, ServerLevel level) {
		long day = level.getDayTime() / 24000L;
		if (CURE_DAY.getOrDefault(cleric.getUUID(), -1L) != day) {
			CURE_DAY.put(cleric.getUUID(), day);
			CURES_TODAY.put(cleric.getUUID(), 0);
		}
		CURES_TODAY.merge(cleric.getUUID(), 1, Integer::sum);
	}

	private static Item heldItemFor(Kind kind) {
		return switch (kind) {
			case SHEAR -> Items.SHEARS;
			case FISH -> Items.FISHING_ROD;
			case BREED -> Items.WHEAT;
			case SURVEY -> Items.MAP;
			default -> Items.AIR;
		};
	}

	private static void hold(Villager villager, Item item) {
		if (villager.isTrading()) {
			return;
		}
		villager.setItemSlot(EquipmentSlot.MAINHAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
		villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	private static void effect(ServerLevel level, BlockPos pos, ParticleOptions particle, SoundEvent sound) {
		level.sendParticles(particle, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, pos, sound, SoundSource.NEUTRAL, 0.5F, 1.0F);
	}

	/** Whether the villager is busy with a profession task right now (they talk about their work). */
	public static boolean isWorking(UUID villager) {
		return TASKS.containsKey(villager);
	}

	/** Village-wide effects of professions (spec v2-GĐ 4.2), from the adults counted in the last update. */
	public record Bonuses(int safety, double buildSpeed, int radius) {
	}

	public static Bonuses bonuses(List<Villager> adults) {
		LVConfig config = LVConfig.get();
		int smiths = 0;
		int toolsmiths = 0;
		boolean cartographer = false;
		for (Villager villager : adults) {
			VillagerProfession profession = villager.getVillagerData().getProfession();
			if ((profession == VillagerProfession.ARMORER || profession == VillagerProfession.WEAPONSMITH) && isEnabled(profession)) {
				smiths++;
			} else if (profession == VillagerProfession.TOOLSMITH && isEnabled(profession)) {
				toolsmiths++;
			} else if (profession == VillagerProfession.CARTOGRAPHER && isEnabled(profession)) {
				cartographer = true;
			}
		}
		if (!config.workEnabled) {
			return new Bonuses(0, 0, 0);
		}
		return new Bonuses(
				Math.min(config.smithSafetyMax, smiths * config.smithSafetyBonus),
				Math.min(config.toolsmithBuildSpeedMax, toolsmiths * config.toolsmithBuildSpeedBonus),
				cartographer ? config.cartographerRadiusBonus : 0);
	}
}
