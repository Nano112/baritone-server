package com.baritoneserver.api;

import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.UUID;

/**
 * Manages mobs under Baritone pathfinding control.
 * Obtained via {@link BaritoneAPI#getMobManager()}.
 */
public interface IMobPathfindingManager {

    /**
     * Register a mob for Baritone pathfinding control.
     * This disables the mob's vanilla AI (both GoalSelector and Brain behaviors).
     *
     * @param mob the mob to register
     * @return the managed mob handle for issuing commands
     */
    IManagedMob registerMob(Mob mob);

    /** Unregister a mob, restoring it to vanilla behavior. */
    void unregisterMob(UUID uuid);

    /** Get a managed mob by UUID, or null if not registered. */
    IManagedMob getManagedMob(UUID uuid);

    /** All currently managed mobs. */
    Map<UUID, ? extends IManagedMob> getManagedMobs();

    /** Stop all managed mobs. */
    void stopAll();
}
