package com.khanhvu.livingvillages.worker;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.build.BuildProject;
import com.khanhvu.livingvillages.build.BuildStep;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.village.VillageAnalyzer;
import com.khanhvu.livingvillages.village.VillageRecord;
import com.khanhvu.livingvillages.village.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Picks the villager who builds a project and walks them to the work. The villager brain keeps choosing its own
 * walk targets (so do mods like Guard Villagers): staffing is checked every {@link #UPDATE_INTERVAL} ticks, and the walk
 * target is restored on any tick it was replaced ({@link #walkTo}).
 */
public final class BuilderAssignment {
	public static final int UPDATE_INTERVAL = 20;
	private static final float WALK_SPEED = 0.6F;
	private static final int CLOSE_ENOUGH = 2;

	private BuilderAssignment() {
	}

	/** The assigned builder if it is loaded and alive, else null. */
	@Nullable
	public static Villager getBuilder(ServerLevel level, BuildProject project) {
		UUID uuid = project.getBuilderUuid();
		if (uuid == null) {
			return null;
		}
		Entity entity = level.getEntity(uuid);
		return entity instanceof Villager villager && villager.isAlive() ? villager : null;
	}

	/**
	 * Keeps the project staffed: replaces a builder that is gone, or that has not reached the work for
	 * builderStuckTicks, then sends the builder towards the current step. Returns the builder, or null when nobody
	 * is available and the project continues without one.
	 */
	@Nullable
	public static Villager update(ServerLevel level, VillageRecord village, BuildProject project, BuildStep step) {
		LVConfig config = LVConfig.get();
		Villager builder = getBuilder(level, project);
		if (builder == null) {
			// Dead, despawned or in an unloaded chunk: either way it cannot work here.
			builder = assign(level, village, project);
		} else if (builder.getUUID().equals(village.getLeaderUuid())) {
			// The leader decides, it does not build (spec v2-GĐ 2.3).
			release(builder);
			builder = assign(level, village, project);
		} else if (isInReach(builder, step, project)) {
			project.setTicksOutOfReach(0);
		} else {
			project.setTicksOutOfReach(project.getTicksOutOfReach() + UPDATE_INTERVAL);
			if (project.getTicksOutOfReach() >= config.builderStuckTicks) {
				LivingVillages.debug("Builder {} stuck, choosing another", builder.getUUID());
				project.excludeBuilder(builder.getUUID());
				release(builder);
				builder = assign(level, village, project);
			}
		}
		if (builder != null) {
			walkTo(builder, step, project);
		}
		return builder;
	}

	/**
	 * Sends the builder to the current step. Cheap enough to call every tick: the memory is only written when the
	 * brain replaced it with a target of its own (idle strolls, gossip, other mods' tasks), so the builder does not
	 * wander off between updates.
	 */
	public static void walkTo(Villager builder, BuildStep step, BuildProject project) {
		if (builder.isTrading()) {
			return;
		}
		BlockPos anchor = step.anchor();
		walkTo(builder, new BlockPos(anchor.getX(), Math.max(anchor.getY(), project.getFloorY()), anchor.getZ()), anchor);
	}

	/** Sends a villager to {@code target}, looking at {@code lookAt}; only writes the brain when it changed its mind. */
	public static void walkTo(Villager villager, BlockPos target, BlockPos lookAt) {
		if (villager.isTrading()) {
			return;
		}
		boolean current = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
				.map(walk -> walk.getTarget().currentBlockPosition().equals(target))
				.orElse(false);
		if (!current) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
			villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(lookAt));
		}
	}

	/**
	 * Close enough to work on the step. Distance is horizontal: builders stay on the ground, so a roof block right
	 * above them is in reach (the vertical offset is bounded by the house height).
	 */
	public static boolean isInReach(Villager builder, BuildStep step, BuildProject project) {
		int reach = LVConfig.get().builderReach;
		BlockPos anchor = step.anchor();
		double dx = builder.getX() - (anchor.getX() + 0.5);
		double dz = builder.getZ() - (anchor.getZ() + 0.5);
		double dy = Math.abs(builder.getY() - anchor.getY());
		return dx * dx + dz * dz <= (double) reach * reach && dy <= project.getFootprint().getYSpan() + reach;
	}

	/** Visual feedback after the builder placed a step. */
	public static void onPlaced(Villager builder, BuildStep step) {
		builder.swing(InteractionHand.MAIN_HAND);
		if (!LVConfig.get().showBuilderHeldItem) {
			return;
		}
		Item item = heldItemFor(step);
		if (item != Items.AIR && !builder.getMainHandItem().is(item)) {
			builder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
			builder.setDropChance(EquipmentSlot.MAINHAND, 0.0F); // never drop free blocks
		}
	}

	/** Frees the project's builder (end, cancel or replacement): empty hands, no forced walk target left behind. */
	public static void release(ServerLevel level, BuildProject project) {
		Villager builder = getBuilder(level, project);
		if (builder != null) {
			release(builder);
		}
		project.setBuilderUuid(null);
	}

	private static void release(Villager builder) {
		if (LVConfig.get().showBuilderHeldItem && !builder.isTrading()) {
			builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		builder.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
	}

	@Nullable
	private static Villager assign(ServerLevel level, VillageRecord village, BuildProject project) {
		Villager chosen = choose(level, village, project);
		project.setBuilderUuid(chosen == null ? null : chosen.getUUID());
		if (chosen != null) {
			LivingVillages.debug("Builder {} assigned to {}", chosen.getUUID(), project.getTemplateId());
		}
		return chosen;
	}

	/** Unemployed first, then masons, then anyone; never nitwits, children or another project's builder. */
	@Nullable
	private static Villager choose(ServerLevel level, VillageRecord village, BuildProject project) {
		Set<UUID> busy = new HashSet<>();
		for (VillageRecord other : VillageRegistry.get(level).getVillages()) {
			BuildProject otherProject = other.getProject();
			if (otherProject != null && otherProject != project && otherProject.getBuilderUuid() != null) {
				busy.add(otherProject.getBuilderUuid());
			}
		}
		BlockPos center = project.getFootprint().getCenter();
		List<Villager> adults = VillageAnalyzer.getAdultVillagers(level, village.getBellPos(), VillageAnalyzer.areaRadius(village));
		Villager best = null;
		int bestRank = Integer.MAX_VALUE;
		double bestDist = Double.MAX_VALUE;
		for (Villager villager : adults) {
			VillagerProfession profession = villager.getVillagerData().getProfession();
			if (profession == VillagerProfession.NITWIT || busy.contains(villager.getUUID()) || project.isExcluded(villager.getUUID())
					|| villager.getUUID().equals(village.getLeaderUuid())) {
				continue;
			}
			int rank = profession == VillagerProfession.NONE ? 0 : profession == VillagerProfession.MASON ? 1 : 2;
			double dist = villager.distanceToSqr(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
			if (rank < bestRank || (rank == bestRank && dist < bestDist)) {
				best = villager;
				bestRank = rank;
				bestDist = dist;
			}
		}
		return best;
	}

	private static Item heldItemFor(BuildStep step) {
		return switch (step) {
			case BuildStep.ChopTree chop -> Items.IRON_AXE;
			case BuildStep.Foundation foundation -> foundation.state().getBlock().asItem();
			case BuildStep.Place place -> place.blocks().get(0).state().getBlock().asItem();
			case BuildStep.Clear clear -> Items.AIR;
		};
	}
}
