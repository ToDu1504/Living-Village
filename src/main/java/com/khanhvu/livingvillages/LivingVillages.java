package com.khanhvu.livingvillages;

import com.khanhvu.livingvillages.build.HouseTemplateProvider;
import com.khanhvu.livingvillages.command.LVCommands;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.NamePool;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LivingVillages implements ModInitializer {
	public static final String MOD_ID = "livingvillages";
	public static final Logger LOGGER = LoggerFactory.getLogger("LivingVillages");

	@Override
	public void onInitialize() {
		LVConfig.load();
		LVText.load();
		NamePool.load();
		VillageTicker.register();
		VillageSociety.register();
		LVCommands.register();
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> HouseTemplateProvider.clearCache());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> HouseTemplateProvider.clearCache());
		LOGGER.info("[LivingVillages] loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Logs only when debugLogging is enabled in the config. */
	public static void debug(String message, Object... args) {
		if (LVConfig.get().debugLogging) {
			LOGGER.info("[LivingVillages] " + message, args);
		}
	}
}
