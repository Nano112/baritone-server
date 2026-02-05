package com.baritoneserver.api;

/**
 * Static entry point for the Baritone Server API.
 * <p>
 * Usage:
 * <pre>{@code
 * IMobPathfindingManager manager = BaritoneAPI.getMobManager();
 * IManagedMob managed = manager.registerMob(myZombie);
 * managed.pathTo(new BlockPos(100, 64, 200));
 * }</pre>
 */
public final class BaritoneAPI {

    private static IMobPathfindingManager manager;

    private BaritoneAPI() {}

    /** Get the mob pathfinding manager. */
    public static IMobPathfindingManager getMobManager() {
        if (manager == null) {
            throw new IllegalStateException("BaritoneAPI not initialized — is the baritone-server mod loaded?");
        }
        return manager;
    }

    /** Called internally during mod initialization. */
    public static void init(IMobPathfindingManager mgr) {
        manager = mgr;
    }
}
