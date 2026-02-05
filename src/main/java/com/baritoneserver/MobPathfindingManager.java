package com.baritoneserver;

import com.baritoneserver.api.IManagedMob;
import com.baritoneserver.api.IMobPathfindingManager;
import com.baritoneserver.api.pathing.calc.IPath;
import com.baritoneserver.api.pathing.goals.Goal;
import com.baritoneserver.api.pathing.goals.GoalBlock;
import com.baritoneserver.api.utils.BetterBlockPos;
import com.baritoneserver.api.utils.PathCalculationResult;
import com.baritoneserver.context.MobEntityContext;
import com.baritoneserver.movement.MobMovementExecutor;
import com.baritoneserver.pathing.calc.AStarPathFinder;
import com.baritoneserver.pathing.movement.CalculationContext;
import com.baritoneserver.visualization.PathVisualizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Tracks mobs under Baritone pathfinding control.
 * Path calculations run asynchronously on a dedicated thread to avoid blocking the server tick.
 */
public class MobPathfindingManager implements IMobPathfindingManager {
    public static final MobPathfindingManager INSTANCE = new MobPathfindingManager();

    private final Map<UUID, ManagedMob> managedMobs = new ConcurrentHashMap<>();

    /** Single-thread executor for path calculations — prevents blocking the server tick */
    private static final ExecutorService PATH_CALC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "baritone-pathfinder");
        t.setDaemon(true);
        return t;
    });

    private boolean visualizationEnabled = false;

    private MobPathfindingManager() {}

    @Override
    public IManagedMob registerMob(Mob mob) {
        ManagedMob managed = new ManagedMob(mob);
        managedMobs.put(mob.getUUID(), managed);
        BaritoneServerMod.LOGGER.info("Registered mob {} ({})", mob.getType().toShortString(), mob.getUUID());
        return managed;
    }

    @Override
    public void unregisterMob(UUID uuid) {
        ManagedMob removed = managedMobs.remove(uuid);
        if (removed != null) {
            PathVisualizer.INSTANCE.clearVisualization(uuid);
            BaritoneServerMod.LOGGER.info("Unregistered mob {}", uuid);
        }
    }

    @Override
    public IManagedMob getManagedMob(UUID uuid) {
        return managedMobs.get(uuid);
    }

    @Override
    public Map<UUID, ? extends IManagedMob> getManagedMobs() {
        return Collections.unmodifiableMap(managedMobs);
    }

    @Override
    public void stopAll() {
        managedMobs.values().forEach(ManagedMob::stop);
    }

    // ===== Internal command helpers (used by BaritoneCommands) =====

    public void commandGoto(UUID mobUuid, BlockPos target) {
        ManagedMob managed = managedMobs.get(mobUuid);
        if (managed != null) {
            managed.pathTo(target);
        }
    }

    public void commandGotoAll(BlockPos target) {
        managedMobs.values().forEach(m -> m.pathTo(target));
    }

    public void commandFollow(UUID mobUuid, Entity target) {
        ManagedMob managed = managedMobs.get(mobUuid);
        if (managed != null) {
            managed.follow(target);
        }
    }

    public void commandFollowAll(Entity target) {
        managedMobs.values().forEach(m -> m.follow(target));
    }

    public void stop(UUID uuid) {
        ManagedMob managed = managedMobs.get(uuid);
        if (managed != null) {
            managed.stop();
        }
    }

    public ManagedMob findByPartialUuid(String partial) {
        String lower = partial.toLowerCase();
        for (var entry : managedMobs.entrySet()) {
            if (entry.getKey().toString().toLowerCase().startsWith(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Internal typed access for commands that need ManagedMob details. */
    public Map<UUID, ManagedMob> getManagedMobsInternal() {
        return managedMobs;
    }

    public boolean isVisualizationEnabled() {
        return visualizationEnabled;
    }

    public void setVisualizationEnabled(boolean enabled) {
        this.visualizationEnabled = enabled;
        if (!enabled) {
            PathVisualizer.INSTANCE.clearAll();
        }
    }

    public void onServerTick(MinecraftServer server) {
        var iterator = managedMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ManagedMob managed = entry.getValue();
            Mob mob = managed.getMob();

            if (mob.isRemoved() || !mob.isAlive()) {
                PathVisualizer.INSTANCE.clearVisualization(entry.getKey());
                iterator.remove();
                continue;
            }

            managed.tick();
        }
    }

    /** Shutdown the path calculation executor. Call on server stop. */
    public static void shutdown() {
        PATH_CALC_EXECUTOR.shutdownNow();
    }

    public static class ManagedMob implements IManagedMob {
        private final Mob mob;
        private final MobEntityContext entityContext;
        private Goal goal;
        private BlockPos target;
        private Entity followTarget;
        private double followStopDistance = 3.0;
        private boolean active;
        private MobMovementExecutor executor;
        private int recalcCooldown;
        private int totalPathCalcs;
        private int totalPathSegments;
        private long lastCalcTimeMs;
        private PathCalculationResult.Type lastCalcType;
        private static final int RECALC_COOLDOWN_TICKS = 5;
        private static final int FOLLOW_RECALC_INTERVAL = 20;
        private int followRecalcTimer;

        private static final long PRIMARY_TIMEOUT = 500;
        private static final long FAILURE_TIMEOUT = 2000;

        /** Async path calculation future — non-null when calculating */
        private CompletableFuture<PathCalculationResult> pendingCalc;
        private long calcStartTime;

        private Runnable pathCompleteCallback;
        private Runnable pathFailedCallback;

        public ManagedMob(Mob mob) {
            this.mob = mob;
            this.entityContext = new MobEntityContext(mob);
            this.active = false;

            // Disable ALL vanilla AI:
            // 1. Clear GoalSelector goals (zombies, skeletons, spiders, etc.)
            mob.removeAllGoals(g -> true);
            // 2. Clear Brain behaviors (villagers, piglins, etc.)
            //    Without this, brain-based mobs fight our MoveControl every tick.
            mob.getBrain().removeAllBehaviors();
            // 3. Stop any active navigation path
            mob.getNavigation().stop();
        }

        @Override
        public Mob getMob() { return mob; }

        @Override
        public BlockPos getTarget() { return target; }

        @Override
        public boolean isActive() { return active; }

        public MobMovementExecutor getExecutor() { return executor; }

        @Override
        public Entity getFollowTarget() { return followTarget; }

        @Override
        public int getTotalPathCalcs() { return totalPathCalcs; }

        @Override
        public long getLastCalcTimeMs() { return lastCalcTimeMs; }

        public PathCalculationResult.Type getLastCalcType() { return lastCalcType; }

        @Override
        public void pathTo(BlockPos target) {
            this.target = target;
            this.goal = new GoalBlock(target);
            this.followTarget = null;
            this.active = true;
            this.executor = null;
            this.pendingCalc = null;
            this.recalcCooldown = 0;
        }

        @Override
        public void pathTo(Goal goal) {
            this.goal = goal;
            this.target = null;
            this.followTarget = null;
            this.active = true;
            this.executor = null;
            this.pendingCalc = null;
            this.recalcCooldown = 0;
        }

        @Override
        public void follow(Entity target) {
            follow(target, 3.0);
        }

        @Override
        public void follow(Entity target, double stopDistance) {
            this.followTarget = target;
            this.followStopDistance = stopDistance;
            this.target = target.blockPosition();
            this.goal = new GoalBlock(this.target);
            this.active = true;
            this.executor = null;
            this.pendingCalc = null;
            this.recalcCooldown = 0;
            this.followRecalcTimer = 0;
        }

        @Override
        public void stop() {
            this.active = false;
            this.target = null;
            this.goal = null;
            this.followTarget = null;
            this.executor = null;
            this.pendingCalc = null;
            mob.setSprinting(false);
            mob.getNavigation().stop();
            PathVisualizer.INSTANCE.clearVisualization(mob.getUUID());
        }

        @Override
        public String getStatusString() {
            if (!active) return "idle";
            if (followTarget != null) return "following " + followTarget.getName().getString();
            if (pendingCalc != null) return "calculating...";
            if (executor != null) {
                return String.format("moving %d/%d [%s]",
                        executor.getPathIndex(), executor.getPathLength(),
                        executor.getMovementTypeDebug());
            }
            if (recalcCooldown > 0) return "waiting (" + recalcCooldown + " ticks)";
            return "calculating";
        }

        @Override
        public int getPathIndex() {
            return executor != null ? executor.getPathIndex() : 0;
        }

        @Override
        public int getPathLength() {
            return executor != null ? executor.getPathLength() : 0;
        }

        @Override
        public List<BetterBlockPos> getCurrentPath() {
            return executor != null ? executor.getPath() : null;
        }

        @Override
        public void setPathCompleteCallback(Runnable callback) {
            this.pathCompleteCallback = callback;
        }

        @Override
        public void setPathFailedCallback(Runnable callback) {
            this.pathFailedCallback = callback;
        }

        public void tick() {
            if (!active) return;

            // Check for completed async calculation
            if (pendingCalc != null) {
                if (pendingCalc.isDone()) {
                    consumePathResult();
                }
                // Still calculating — don't do anything else, just wait
                return;
            }

            // Follow mode: update target position periodically
            if (followTarget != null) {
                if (followTarget.isRemoved() || !followTarget.isAlive()) {
                    BaritoneServerMod.LOGGER.info("Follow target lost for mob {}",
                            mob.getUUID().toString().substring(0, 8));
                    stop();
                    firePathFailed();
                    return;
                }
                followRecalcTimer++;
                if (followRecalcTimer >= FOLLOW_RECALC_INTERVAL) {
                    followRecalcTimer = 0;
                    BlockPos newTarget = followTarget.blockPosition();
                    if (!newTarget.equals(target)) {
                        target = newTarget;
                        goal = new GoalBlock(target);
                        Vec3 mobPos = mob.position();
                        double distSq = mobPos.distanceToSqr(newTarget.getX() + 0.5, newTarget.getY(), newTarget.getZ() + 0.5);
                        if (distSq > 16) {
                            executor = null;
                            recalcCooldown = 0;
                        }
                    }
                }
            }

            if (goal == null) return;

            // Check if already at goal
            Vec3 mobPos = mob.position();
            int mx = (int) Math.floor(mobPos.x);
            int my = (int) Math.floor(mobPos.y);
            int mz = (int) Math.floor(mobPos.z);

            if (followTarget != null) {
                // Follow mode: distance-based arrival check
                double distSq = mobPos.distanceToSqr(
                        followTarget.getX(), followTarget.getY(), followTarget.getZ());
                if (distSq < followStopDistance * followStopDistance) {
                    executor = null;
                    mob.setSprinting(false);
                    return;
                }
            } else if (goal.isInGoal(mx, my, mz)) {
                BaritoneServerMod.LOGGER.info("Mob {} reached goal",
                        mob.getUUID().toString().substring(0, 8));
                active = false;
                executor = null;
                mob.setSprinting(false);
                firePathComplete();
                return;
            }

            // Execute current path
            if (executor != null) {
                boolean continuing = executor.tick();
                if (!continuing) {
                    if (executor.isComplete()) {
                        totalPathSegments++;
                        executor = null;
                        recalcCooldown = 0;
                    } else {
                        BaritoneServerMod.LOGGER.debug("Path execution stopped for mob {}, will recalculate",
                                mob.getUUID().toString().substring(0, 8));
                        executor = null;
                        recalcCooldown = RECALC_COOLDOWN_TICKS;
                    }
                }
                return;
            }

            if (recalcCooldown > 0) {
                recalcCooldown--;
                return;
            }

            calculatePathAsync();
        }

        /** Start an async path calculation on the dedicated thread */
        private void calculatePathAsync() {
            BetterBlockPos start = entityContext.entityFeet();

            // Build context on main thread (reads mob attributes)
            CalculationContext calcCtx = new CalculationContext(entityContext);

            AStarPathFinder pathfinder = new AStarPathFinder(
                    start, start.x, start.y, start.z,
                    goal, calcCtx
            );

            calcStartTime = System.currentTimeMillis();
            totalPathCalcs++;

            // Submit calculation to worker thread
            pendingCalc = CompletableFuture.supplyAsync(
                    () -> pathfinder.calculate(PRIMARY_TIMEOUT, FAILURE_TIMEOUT),
                    PATH_CALC_EXECUTOR
            );
        }

        /** Consume the result of an async path calculation (called on main thread) */
        private void consumePathResult() {
            PathCalculationResult result;
            try {
                result = pendingCalc.get(); // already done, won't block
            } catch (Exception e) {
                BaritoneServerMod.LOGGER.error("Path calculation failed with exception: {}", e.getMessage());
                pendingCalc = null;
                recalcCooldown = RECALC_COOLDOWN_TICKS * 10;
                firePathFailed();
                return;
            }
            pendingCalc = null;

            lastCalcTimeMs = System.currentTimeMillis() - calcStartTime;
            lastCalcType = result.getType();

            Optional<IPath> pathOpt = result.getPath();
            if (pathOpt.isPresent()) {
                IPath path = pathOpt.get();
                BaritoneServerMod.LOGGER.info("Path found for mob {}: {} positions in {}ms ({})",
                        mob.getUUID().toString().substring(0, 8),
                        path.length(), lastCalcTimeMs, result.getType());
                executor = new MobMovementExecutor(mob, path);

                // Update visualization if enabled
                if (MobPathfindingManager.INSTANCE.isVisualizationEnabled()) {
                    ServerLevel world = (ServerLevel) mob.level();
                    PathVisualizer.INSTANCE.visualizePath(world, executor.getPath(), mob.getUUID());
                }
            } else {
                BaritoneServerMod.LOGGER.warn("No path found for mob {} in {}ms ({})",
                        mob.getUUID().toString().substring(0, 8),
                        lastCalcTimeMs, result.getType());
                recalcCooldown = RECALC_COOLDOWN_TICKS * 10;
                firePathFailed();
            }
        }

        private void firePathComplete() {
            if (pathCompleteCallback != null) {
                try {
                    pathCompleteCallback.run();
                } catch (Exception e) {
                    BaritoneServerMod.LOGGER.error("Path complete callback threw exception", e);
                }
            }
        }

        private void firePathFailed() {
            if (pathFailedCallback != null) {
                try {
                    pathFailedCallback.run();
                } catch (Exception e) {
                    BaritoneServerMod.LOGGER.error("Path failed callback threw exception", e);
                }
            }
        }
    }
}
