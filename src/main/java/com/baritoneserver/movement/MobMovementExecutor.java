package com.baritoneserver.movement;

import com.baritoneserver.BaritoneServerMod;
import com.baritoneserver.api.pathing.calc.IPath;
import com.baritoneserver.api.utils.BetterBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Executes a calculated path by driving the mob's movement controls.
 * Sprint logic is ported from Baritone's PathExecutor.shouldSprintNextTick()
 * and individual movement updateState() methods.
 *
 * Baritone's sprint rules (simplified for mobs):
 * - Only MovementTraverse requests sprint directly
 * - Parkour sprints for dist >= 4 or ascend
 * - Descend, Ascend, Diagonal, Fall do NOT sprint by default
 * - PathExecutor look-ahead handles special combos (traverse->ascend, descend->traverse)
 */
public class MobMovementExecutor {

    private final Mob mob;
    private final List<BetterBlockPos> path;
    private int pathIndex;
    private int ticksOnCurrentNode;
    private Vec3 lastPos;
    private int ticksNotMoving;

    /**
     * Speed modifier for MoveControl to normalize fast mobs (e.g. villagers with 0.5 base speed).
     * Caps effective speed so all mobs move at a reasonable pace, similar to zombie/skeleton speed.
     */
    private final double speedModifier;

    private static final int STUCK_THRESHOLD = 60;
    private static final int NOT_MOVING_THRESHOLD = 30;
    private static final double NOT_MOVING_DIST_SQ = 0.01;
    /** Max base movement speed — mobs faster than this get scaled down. ~zombie sprint speed. */
    private static final double MAX_BASE_SPEED = 0.3;

    public MobMovementExecutor(Mob mob, IPath path) {
        this.mob = mob;
        this.path = path.positions();
        this.pathIndex = 0;
        this.ticksOnCurrentNode = 0;
        this.lastPos = mob.position();
        this.ticksNotMoving = 0;

        // Normalize speed for fast mobs. Villagers have 0.5 base speed vs zombies' 0.23.
        // Cap so no mob moves faster than MAX_BASE_SPEED effective.
        double baseSpeed = mob.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        this.speedModifier = baseSpeed > MAX_BASE_SPEED ? MAX_BASE_SPEED / baseSpeed : 1.0;
    }

    /**
     * @return true if execution is still active, false if complete or failed
     */
    public boolean tick() {
        if (pathIndex >= path.size()) {
            mob.setSprinting(false);
            return false;
        }

        // Stuck detection: check if mob is actually making progress
        Vec3 currentPos = mob.position();
        if (currentPos.distanceToSqr(lastPos) < NOT_MOVING_DIST_SQ) {
            ticksNotMoving++;
        } else {
            ticksNotMoving = 0;
        }
        lastPos = currentPos;

        if (ticksNotMoving > NOT_MOVING_THRESHOLD && mob.onGround()) {
            BaritoneServerMod.LOGGER.debug("Mob not making progress, stuck at index {}", pathIndex);
            mob.setSprinting(false);
            return false;
        }

        // Advance past waypoints we've already passed or are close enough to
        while (pathIndex < path.size() && isAtWaypoint(currentPos, path.get(pathIndex))) {
            pathIndex++;
            ticksOnCurrentNode = 0;
        }

        if (pathIndex >= path.size()) {
            mob.setSprinting(false);
            return false;
        }

        ticksOnCurrentNode++;
        if (ticksOnCurrentNode > STUCK_THRESHOLD) {
            BaritoneServerMod.LOGGER.debug("Mob stuck at node {} for {} ticks", pathIndex, ticksOnCurrentNode);
            mob.setSprinting(false);
            return false;
        }

        // Classify movement and execute
        BetterBlockPos prev = pathIndex > 0 ? path.get(pathIndex - 1) : new BetterBlockPos(
                Mth.floor(currentPos.x), Mth.floor(currentPos.y), Mth.floor(currentPos.z));
        BetterBlockPos target = path.get(pathIndex);

        int dx = target.x - prev.x;
        int dy = target.y - prev.y;
        int dz = target.z - prev.z;
        int horizontalDist = Math.abs(dx) + Math.abs(dz);

        MovementType type = classifyMovement(dx, dy, dz, horizontalDist);

        switch (type) {
            case TRAVERSE -> executeTraverse(currentPos, target);
            case ASCEND -> executeAscend(currentPos, target);
            case DESCEND -> executeDescend(currentPos, target);
            case FALL -> executeFall(currentPos, target);
            case DIAGONAL -> executeDiagonal(currentPos, target);
            case PARKOUR -> executeParkour(currentPos, target, prev, dy);
            case DOWNWARD -> executeDownward(currentPos, target);
            case SWIM -> executeSwim(currentPos, target);
        }

        return true;
    }

    private boolean isAtWaypoint(Vec3 pos, BetterBlockPos waypoint) {
        double dx = (waypoint.x + 0.5) - pos.x;
        double dz = (waypoint.z + 0.5) - pos.z;
        double horizontalDistSq = dx * dx + dz * dz;
        double dy = waypoint.y - pos.y;

        // For descend/fall, be more lenient on Y
        if (dy < -0.5) {
            return horizontalDistSq < 0.8 * 0.8 && Math.abs(dy) < 2.5;
        }
        return horizontalDistSq < 0.5 * 0.5 && Math.abs(dy) < 1.0;
    }

    private MovementType classifyMovement(int dx, int dy, int dz, int horizontalDist) {
        // Check if mob is in water — override to swim
        if (mob.isInWater()) {
            return MovementType.SWIM;
        }

        if (dy == 0 && horizontalDist == 1) return MovementType.TRAVERSE;
        if (dy == 1 && horizontalDist == 1) return MovementType.ASCEND;
        if (dy == -1 && horizontalDist == 1) return MovementType.DESCEND;
        if (dy == -1 && horizontalDist == 0) return MovementType.DOWNWARD;
        if (dy < -1) return MovementType.FALL;
        if (dy == 0 && Math.abs(dx) == 1 && Math.abs(dz) == 1) return MovementType.DIAGONAL;
        // Parkour: horizontalDist > 1 covers both flat parkour and parkour ascend (dy=0 or dy=1)
        if (horizontalDist > 1) return MovementType.PARKOUR;
        if (dy == 1 && horizontalDist == 0) return MovementType.ASCEND;
        if (dy == 0 && horizontalDist == 0) return MovementType.TRAVERSE;

        // Diagonal ascend: dy=1 with diagonal movement
        if (dy == 1 && Math.abs(dx) == 1 && Math.abs(dz) == 1) return MovementType.DIAGONAL;
        if (dy >= 1) return MovementType.ASCEND;
        return MovementType.TRAVERSE;
    }

    // ===== Movement Execution =====
    // Sprint logic ported from Baritone:
    // - Only traverse requests sprint (via shouldSprintNextTick look-ahead)
    // - Parkour sprints for dist >= 4 or ascend
    // - All other movement types walk

    /**
     * Traverse: the only movement that requests sprint.
     * Baritone's MovementTraverse.updateState() sets Input.SPRINT when:
     * - Bridge block was always there (not placed)
     * - Not in liquid
     * - Blocks ahead aren't dangerous
     * Then PathExecutor.shouldSprintNextTick() can override based on look-ahead.
     */
    private void executeTraverse(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;

        boolean shouldSprint = shouldSprintNextTick();
        faceAndMoveToward(pos, tx, target.y, tz, shouldSprint);
    }

    /**
     * Ascend: no sprint. Baritone's MovementAscend does NOT request sprint.
     * The traverse->ascend sprint-jump combo is handled by PathExecutor skipping
     * the traverse — we can't do that with mobs, so ascend is always walk.
     */
    private void executeAscend(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;
        double dx = tx - pos.x;
        double dz = tz - pos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // No sprint during ascend
        mob.setSprinting(false);
        faceAndMoveToward(pos, tx, target.y, tz, false);

        // Jump when close enough to the base and on the ground
        if (mob.onGround() && horizontalDist < 1.4) {
            mob.getJumpControl().jump();
        }
    }

    /**
     * Descend: Baritone's MovementDescend doesn't request sprint itself, but
     * PathExecutor.shouldSprintNextTick() enables sprint for:
     * - Chained descends in the same direction
     * - Descend followed by traverse in same direction
     * This avoids the "slow staircase" problem.
     */
    private void executeDescend(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;

        boolean shouldSprint = canSprintFromDescend();
        faceAndMoveToward(pos, tx, target.y, tz, shouldSprint);
    }

    /**
     * Ported from Baritone's PathExecutor.canSprintFromDescendInto().
     * Sprint through descend if the next movement continues in the same XZ direction
     * and is either another descend or a traverse.
     */
    private boolean canSprintFromDescend() {
        if (mob.isInWater()) return false;
        if (pathIndex + 1 >= path.size()) return false;
        if (pathIndex < 1) return false;

        BetterBlockPos prev = path.get(pathIndex - 1);
        BetterBlockPos current = path.get(pathIndex);
        int curDx = current.x - prev.x;
        int curDz = current.z - prev.z;

        BetterBlockPos next = path.get(pathIndex + 1);
        int nextDx = next.x - current.x;
        int nextDy = next.y - current.y;
        int nextDz = next.z - current.z;
        int nextHoriz = Math.abs(nextDx) + Math.abs(nextDz);

        // Same XZ direction check
        boolean sameDirection = (curDx == nextDx && curDz == nextDz);

        // Chain descend: next is descend in same direction
        if (sameDirection && nextDy == -1 && nextHoriz == 1) return true;

        // Descend into traverse: next is flat traverse in same direction
        if (sameDirection && nextDy == 0 && nextHoriz == 1) return true;

        return false;
    }

    /** Fall: no sprint. Walk forward and let gravity work. */
    private void executeFall(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;

        mob.setSprinting(false);
        faceAndMoveToward(pos, tx, target.y, tz, false);
    }

    /**
     * Diagonal: Baritone's MovementDiagonal DOES request sprint when the path
     * is clear (no blocks to break). Since our mobs never break blocks, diagonal
     * should always sprint unless in liquid.
     * For diagonal ascend, add jump.
     */
    private void executeDiagonal(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;
        boolean goingUp = target.y > Mth.floor(pos.y);

        // Baritone's MovementDiagonal sprints when path is clear (which it always is for mobs)
        boolean shouldSprint = !mob.isInWater();
        faceAndMoveToward(pos, tx, target.y, tz, shouldSprint);

        // Diagonal ascend: need to jump
        if (goingUp && mob.onGround()) {
            double dx = tx - pos.x;
            double dz = tz - pos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist < 1.8) {
                mob.getJumpControl().jump();
            }
        }
    }

    /**
     * Parkour: always sprint — mobs need the sprint momentum for jumps.
     * In Baritone (player), jumpFromGround() adds +0.2 forward velocity ONLY when sprinting.
     * Without that boost, mobs can't clear 2-block gaps.
     * Jump timing adapted from Baritone's MovementParkour.updateState().
     */
    private void executeParkour(Vec3 pos, BetterBlockPos target, BetterBlockPos prev, int dy) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;

        int absDx = Math.abs(target.x - prev.x);
        int absDz = Math.abs(target.z - prev.z);
        int dist = Math.max(absDx, absDz); // parkour distance (2, 3, or 4 blocks)
        boolean ascend = dy > 0;

        // Always sprint for parkour — the sprint jump boost (+0.2 forward velocity from
        // jumpFromGround) is essential for mobs to clear gaps
        mob.setSprinting(true);
        faceAndMoveToward(pos, tx, target.y, tz, true);

        // Calculate how far we've moved from the jump source
        double dxFromPrev = pos.x - (prev.x + 0.5);
        double dzFromPrev = pos.z - (prev.z + 0.5);
        double distFromStart = Math.sqrt(dxFromPrev * dxFromPrev + dzFromPrev * dzFromPrev);

        // Jump logic from Baritone's MovementParkour.updateState():
        // Jump when we've moved enough from the source block center
        if (mob.onGround() && distFromStart > 0.05) {
            boolean shouldJump;
            if (dist == 3 && !ascend) {
                // 2-block gap: delay jump to 0.7 blocks from center for proper arc
                shouldJump = distFromStart >= 0.7;
            } else {
                // dist 2, 4, or any ascend: jump once we've moved 0.4 blocks
                shouldJump = distFromStart >= 0.4;
            }

            if (shouldJump) {
                mob.getJumpControl().jump();

                // Give the mob a forward velocity boost in the jump direction.
                // This compensates for mobs having weaker air control than players
                // and ensures they carry enough momentum through the jump arc.
                double dirX = (double)(target.x - prev.x) / dist;
                double dirZ = (double)(target.z - prev.z) / dist;
                Vec3 delta = mob.getDeltaMovement();
                double boost = 0.2;
                mob.setDeltaMovement(
                        delta.x + dirX * boost,
                        delta.y,
                        delta.z + dirZ * boost
                );
            }
        }
    }

    /** Downward: no sprint. Just drop. */
    private void executeDownward(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;
        mob.setSprinting(false);
        faceAndMoveToward(pos, tx, target.y, tz, false);
    }

    /** Swim: no sprint. Jump to swim upward, boost velocity. */
    private void executeSwim(Vec3 pos, BetterBlockPos target) {
        double tx = target.x + 0.5;
        double tz = target.z + 0.5;
        double ty = target.y;

        mob.setSprinting(false);
        faceAndMoveToward(pos, tx, ty, tz, false);

        // In water: jump to swim upward when below target
        if (mob.isInWater() && pos.y < ty + 0.6) {
            mob.getJumpControl().jump();
        }

        // Apply slight upward velocity boost to stay afloat
        if (mob.isInWater()) {
            Vec3 delta = mob.getDeltaMovement();
            if (pos.y < ty) {
                mob.setDeltaMovement(delta.x, Math.max(delta.y, 0.04), delta.z);
            }
        }
    }

    // ===== Helpers =====

    private void faceAndMoveToward(Vec3 pos, double tx, double ty, double tz, boolean sprint) {
        double dx = tx - pos.x;
        double dz = tz - pos.z;

        // Calculate yaw toward target
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        mob.setYRot(targetYaw);
        mob.yBodyRot = targetYaw;
        mob.yHeadRot = targetYaw;

        // Set sprinting — this adds a 30% speed modifier to the mob's MOVEMENT_SPEED attribute.
        // Use speedModifier=1.0 so MoveControl doesn't double the sprint boost.
        mob.setSprinting(sprint);

        // MoveControl handles forward movement. speedModifier normalizes fast mobs
        // (e.g. villagers 0.5 base) to move at a reasonable pace.
        // Do NOT also call setZza() — it conflicts with MoveControl.tick().
        mob.getMoveControl().setWantedPosition(tx, ty, tz, speedModifier);

        // Look toward target
        mob.getLookControl().setLookAt(tx, ty + mob.getEyeHeight(), tz);
    }

    /**
     * Ported from Baritone's PathExecutor.shouldSprintNextTick().
     *
     * Baritone's sprint logic for traverse:
     * - Sprint through sequences of flat traverses
     * - Sprint into descends (handled by canSprintFromDescend for descend nodes)
     * - Sprint into ascends when same direction (traverse->ascend sprint-jump)
     * - Don't sprint in water
     * - Don't sprint if upcoming movement is parkour (needs precise positioning)
     */
    private boolean shouldSprintNextTick() {
        if (mob.isInWater()) return false;
        if (pathIndex + 1 >= path.size()) return true; // Last node, sprint is fine

        BetterBlockPos current = path.get(pathIndex);
        BetterBlockPos next = path.get(pathIndex + 1);
        int nextDy = next.y - current.y;
        int nextDx = Math.abs(next.x - current.x);
        int nextDz = Math.abs(next.z - current.z);
        int nextHoriz = nextDx + nextDz;

        // Next is parkour: KEEP sprinting to build momentum for the jump.
        // Mobs need forward velocity when they reach the parkour node.
        if (nextHoriz > 1) return true;

        // Next is ascend in same direction: Baritone sprint-jumps through this.
        // For mobs, we can sprint up to the ascend — the ascend handler will stop sprint.
        if (nextDy == 1 && nextHoriz == 1) {
            // Check if we're heading in the same direction
            if (pathIndex > 0) {
                BetterBlockPos prev = path.get(pathIndex - 1);
                int curDirX = current.x - prev.x;
                int curDirZ = current.z - prev.z;
                int nextDirX = next.x - current.x;
                int nextDirZ = next.z - current.z;
                // Same direction: allow sprint into ascend
                if (curDirX == nextDirX && curDirZ == nextDirZ) {
                    return true;
                }
            }
            // Different direction ascend: slow down
            return false;
        }

        // Next is flat traverse or descend: sprint
        return true;
    }

    // ===== Accessors =====

    public int getPathIndex() {
        return pathIndex;
    }

    public int getPathLength() {
        return path.size();
    }

    public List<BetterBlockPos> getPath() {
        return path;
    }

    public boolean isComplete() {
        return pathIndex >= path.size();
    }

    public BetterBlockPos getCurrentTarget() {
        if (pathIndex < path.size()) {
            return path.get(pathIndex);
        }
        return null;
    }

    public BetterBlockPos getDestination() {
        if (path.isEmpty()) return null;
        return path.get(path.size() - 1);
    }

    public String getMovementTypeDebug() {
        if (pathIndex >= path.size()) return "DONE";
        BetterBlockPos prev = pathIndex > 0 ? path.get(pathIndex - 1) : null;
        BetterBlockPos target = path.get(pathIndex);
        if (prev == null) return "START";
        int dx = target.x - prev.x;
        int dy = target.y - prev.y;
        int dz = target.z - prev.z;
        return classifyMovement(dx, dy, dz, Math.abs(dx) + Math.abs(dz)).name();
    }

    private enum MovementType {
        TRAVERSE,
        ASCEND,
        DESCEND,
        FALL,
        DIAGONAL,
        PARKOUR,
        DOWNWARD,
        SWIM
    }
}
