package com.khanhvu.livingvillages.mixin;

import net.minecraft.world.entity.monster.ZombieVillager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/** Lets a cleric start curing a zombie villager (spec v2-GĐ 4.5); the vanilla cure is private. */
@Mixin(ZombieVillager.class)
public interface ZombieVillagerInvoker {
	@Invoker("startConverting")
	void livingvillages$startConverting(@Nullable UUID starter, int ticks);
}
