package com.baritoneserver;

import com.baritoneserver.api.BaritoneAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaritoneServerMod implements ModInitializer {
    public static final String MOD_ID = "baritone-server";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Baritone Server initializing...");

        BaritoneAPI.init(MobPathfindingManager.INSTANCE);

        CommandRegistrationCallback.EVENT.register(BaritoneCommands::register);
        ServerTickEvents.END_SERVER_TICK.register(server -> MobPathfindingManager.INSTANCE.onServerTick(server));

        // Shutdown the path calculation executor when the server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Shutting down Baritone pathfinding executor...");
            MobPathfindingManager.shutdown();
        });

        LOGGER.info("Baritone Server initialized.");
    }
}
