package com.baritoneserver.api;

import com.baritoneserver.api.pathing.goals.Goal;
import com.baritoneserver.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * A mob under Baritone pathfinding control.
 * Obtained via {@link IMobPathfindingManager#registerMob(Mob)}.
 */
public interface IManagedMob {

    /** The underlying Minecraft mob entity. */
    Mob getMob();

    // ===== Commands =====

    /** Pathfind to an exact block position. */
    void pathTo(BlockPos target);

    /** Pathfind using a custom goal (GoalNear, GoalXZ, GoalBlock, etc.). */
    void pathTo(Goal goal);

    /** Follow an entity, recalculating periodically. */
    void follow(Entity target);

    /** Follow an entity, stopping when within the given distance. */
    void follow(Entity target, double stopDistance);

    /** Stop all movement and clear targets. */
    void stop();

    // ===== State =====

    /** Whether this mob is actively pathfinding or following. */
    boolean isActive();

    /** Current target block position, or null if none. */
    BlockPos getTarget();

    /** Entity being followed, or null. */
    Entity getFollowTarget();

    /** Human-readable status string (idle, calculating, moving, etc.). */
    String getStatusString();

    // ===== Path Info =====

    /** Current index in the path (0-based). Returns 0 if no path. */
    int getPathIndex();

    /** Total waypoints in the current path. Returns 0 if no path. */
    int getPathLength();

    /** The current path positions, or null if no path is being executed. */
    List<BetterBlockPos> getCurrentPath();

    // ===== Stats =====

    /** Total number of path calculations performed for this mob. */
    int getTotalPathCalcs();

    /** Duration of the last path calculation in milliseconds. */
    long getLastCalcTimeMs();

    // ===== Events =====

    /** Set a callback fired when the mob reaches its goal. */
    void setPathCompleteCallback(Runnable callback);

    /** Set a callback fired when pathfinding fails (no path found or stuck). */
    void setPathFailedCallback(Runnable callback);
}
