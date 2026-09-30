package com.khanhvu.livingvillages;

import com.khanhvu.livingvillages.board.MaterialBoard;
import com.khanhvu.livingvillages.build.BuildingTemplateProvider;
import com.khanhvu.livingvillages.chronicle.Chronicle;
import com.khanhvu.livingvillages.command.LVCommands;
import com.khanhvu.livingvillages.config.LVConfig;
import com.khanhvu.livingvillages.identity.NamePool;
import com.khanhvu.livingvillages.identity.LevelUpFireworks;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.road.RoadBuilder;
import com.khanhvu.livingvillages.society.VillageSociety;
import com.khanhvu.livingvillages.tick.VillageTicker;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.voice.VillageVoice;
import com.khanhvu.livingvillages.work.ProfessionWork;
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
		Chronicle.registerEarly();
		VillageSociety.register();
		ProfessionWork.register();
		VillageVoice.register();
		Chronicle.register();
		MaterialBoard.register();
		LevelUpFireworks.register();
		RoadBuilder.register();
		LVCommands.register();
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> BuildingTemplateProvider.clearCache());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> server.getAllLevels().forEach(VillageVoice::clear));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BuildingTemplateProvider.clearCache();
			VillageIdentity.clear();
			Chronicle.clear();
			LevelUpFireworks.clear();
			MaterialBoard.clear();
			RoadBuilder.clear();
		});
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
