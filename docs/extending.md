# Extending Baritone Server

This guide covers how to extend Baritone Server with new functionality — adding movement types, custom goals, block-breaking support, new commands, and more.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Adding a New Goal Type](#adding-a-new-goal-type)
- [Adding New Movement Types](#adding-new-movement-types)
- [Enabling Block Breaking / Placing](#enabling-block-breaking--placing)
- [Per-Mob Configuration](#per-mob-configuration)
- [Adding New Commands](#adding-new-commands)
- [Custom Movement Execution](#custom-movement-execution)
- [Extending the API](#extending-the-api)
- [Key Classes Reference](#key-classes-reference)

---

## Architecture Overview

The pathfinding pipeline has four stages:

```
Goal + Start Position
        │
        ▼
┌─────────────────────┐
│  A* Path Calculation │  (async, off main thread)
│  AStarPathFinder     │
│  Uses: Moves (22)    │
│        MovementCosts  │
│        MovementHelper │
│        CalculationCtx │
└────────┬────────────┘
         │ IPath (list of BetterBlockPos)
         ▼
┌─────────────────────┐
│  Path Execution      │  (main thread, each tick)
│  MobMovementExecutor │
│  Uses: MoveControl   │
│        JumpControl   │
│        LookControl   │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  Lifecycle Mgmt      │  (main thread, each tick)
│  MobPathfindingMgr   │
│  Handles: recalc,    │
│    follow, callbacks  │
└─────────────────────┘
```

**Key separation:** The A* engine only computes costs and positions. It never moves the mob. `MobMovementExecutor` reads the resulting path and drives the mob's controls each tick.

### File Layout

```
com.baritoneserver/
├── api/                          # Public API interfaces
│   ├── BaritoneAPI.java          # Static entry point
│   ├── IManagedMob.java          # Per-mob control interface
│   ├── IMobPathfindingManager.java
│   ├── IEntityContext.java       # Mob + world access
│   ├── pathing/
│   │   ├── calc/IPath.java       # Path result interface
│   │   ├── goals/                # All 9 goal types
│   │   └── movement/ActionCosts.java
│   └── utils/
│       ├── BetterBlockPos.java   # Immutable int position
│       └── PathCalculationResult.java
├── context/
│   └── MobEntityContext.java     # IEntityContext implementation
├── movement/
│   └── MobMovementExecutor.java  # Drives mob along path
├── pathing/
│   ├── calc/
│   │   ├── AStarPathFinder.java  # A* search algorithm
│   │   ├── AbstractNodeCostSearch.java
│   │   └── openset/BinaryHeapOpenSet.java
│   ├── movement/
│   │   ├── CalculationContext.java  # World + mob config for A*
│   │   ├── MovementCosts.java       # Cost per movement type
│   │   ├── MovementHelper.java      # Block walkability checks
│   │   └── Moves.java              # 22 movement directions
│   ├── path/Path.java, CutoffPath.java
│   ├── precompute/PrecomputedData.java
│   └── world/ServerBlockStateInterface.java
├── visualization/PathVisualizer.java
├── MobPathfindingManager.java    # Lifecycle + API impl
├── BaritoneCommands.java         # Op commands
└── BaritoneServerMod.java        # Mod entry point
```

---

## Adding a New Goal Type

Goals define **where** the mob should go. The A* engine uses `heuristic()` to estimate distance and `isInGoal()` to detect arrival.

### The Goal Interface

```java
public interface Goal {
    boolean isInGoal(int x, int y, int z);
    double heuristic(int x, int y, int z);
}
```

- `heuristic()` must be **admissible** — it must never overestimate the actual cost. If it does, A* may not find the optimal path.
- `isInGoal()` is checked both during A* search (to stop early) and during execution (for arrival detection in `MobPathfindingManager`).

### Example: GoalRadius (circular area)

```java
package com.baritoneserver.api.pathing.goals;

public class GoalRadius implements Goal {
    private final int x, y, z;
    private final double radius;

    public GoalRadius(int x, int y, int z, double radius) {
        this.x = x; this.y = y; this.z = z;
        this.radius = radius;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        double dx = x - this.x;
        double dy = y - this.y;
        double dz = z - this.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - this.x;
        double dy = y - this.y;
        double dz = z - this.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Subtract radius so heuristic is 0 when at edge of goal area
        return Math.max(0, dist - radius) * ActionCosts.SPRINT_ONE_BLOCK_COST;
    }
}
```

### Using It

```java
managed.pathTo(new GoalRadius(100, 64, 200, 10.0));
```

No other code changes needed — the A* engine and arrival detection work entirely through the `Goal` interface.

### Existing Goal Types for Reference

| Goal | Constructor | Behavior |
|------|-------------|----------|
| `GoalBlock` | `(BlockPos)` | Exact block position |
| `GoalNear` | `(BlockPos, int range)` | Within range blocks (Manhattan-ish) |
| `GoalXZ` | `(int x, int z)` | Specific X/Z, any Y |
| `GoalYLevel` | `(int y)` | Specific Y, any X/Z |
| `GoalGetToBlock` | `(BlockPos)` | Adjacent to block (for interactions) |
| `GoalTwoBlocks` | `(BlockPos)` | Feet or head at position |
| `GoalRunAway` | `(double dist, BlockPos...)` | At least N blocks from positions |
| `GoalComposite` | `(Goal...)` | Any sub-goal reached (OR) |
| `GoalInverted` | `(Goal)` | Negated heuristic (repulsion) |

---

## Adding New Movement Types

The A* engine considers 22 movement directions defined in `Moves.java`. Each direction has a cost function in `MovementCosts.java` and block-checking via `MovementHelper.java`.

### How Moves Work

Each `Moves` enum value defines:

```java
TRAVERSE_NORTH(0, 0, -1) {
    @Override
    public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult res) {
        res.x = x + 0;
        res.y = y;
        res.z = z + (-1);
        res.cost = MovementCosts.traverse(ctx, x, y, z, res.x, res.z);
    }
}
```

The A* engine calls `apply()` for every node to compute neighbor costs. `MutableMoveResult` carries the destination coordinates and cost back.

### Adding a New Direction

1. **Add the enum value** in `Moves.java`:

```java
// Example: a "leap" movement — jump 2 blocks forward + 1 up
LEAP_NORTH(0, 1, -2) {
    @Override
    public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult res) {
        res.x = x;
        res.y = y + 1;
        res.z = z - 2;
        res.cost = MovementCosts.leap(ctx, x, y, z, res.x, res.z);
    }
}
```

2. **Add the cost function** in `MovementCosts.java`:

```java
public static double leap(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
    // Check destination is walkable
    if (!canWalkOn(ctx, destX, y, destZ)) return COST_INF;
    // Check clearance at destination (2 blocks tall)
    if (!canWalkThrough(ctx, destX, y + 1, destZ)) return COST_INF;
    if (!canWalkThrough(ctx, destX, y + 2, destZ)) return COST_INF;
    // Check air above start for jump
    if (!canWalkThrough(ctx, x, y + 2, z)) return COST_INF;
    return JUMP_ONE_BLOCK_COST + SPRINT_ONE_BLOCK_COST * 2;
}
```

3. **Add execution logic** in `MobMovementExecutor.java`:

Update `classifyMovement()` to detect the new movement pattern, then add an `executeLeap()` method that drives the mob's controls.

### Movement Cost Guidelines

- Return `ActionCosts.COST_INF` when a movement is impossible
- Use `MovementHelper.canWalkThrough()` for passability checks
- Use `MovementHelper.canWalkOn()` for floor checks
- Check 2 blocks of headroom (mob height)
- Consider sprint vs walk costs via `ActionCosts.SPRINT_ONE_BLOCK_COST` / `ActionCosts.WALK_ONE_BLOCK_COST`

### The 22 Existing Directions

| Category | Directions | Notes |
|----------|-----------|-------|
| Traverse | N, S, E, W | Flat horizontal movement |
| Ascend | N, S, E, W | +1 Y with jump |
| Descend | N, S, E, W | -1 Y stepping down (can chain into falls) |
| Diagonal | NE, NW, SE, SW | 2D diagonal movement |
| Parkour | N, S, E, W | 2-4 block gaps, variable distance |
| Downward | (0,-1,0) | Straight down |
| Pillar | (0,+1,0) | Straight up (currently COST_INF — mobs can't place) |

---

## Enabling Block Breaking / Placing

Currently mobs cannot break or place blocks. This is controlled in `CalculationContext.java`:

```java
public final boolean allowBreak = false;
public final boolean allowPlace = false;
```

And in cost functions:

```java
public double costOfPlacingAt(int x, int y, int z, BlockState current) {
    return COST_INF;  // Blocks placing
}

public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
    return COST_INF;  // Blocks breaking
}
```

### Steps to Enable

1. **Make flags configurable** in `CalculationContext`:

```java
public CalculationContext(IEntityContext entityContext, boolean allowBreak, boolean allowPlace) {
    // ...
    this.allowBreak = allowBreak;
    this.allowPlace = allowPlace;
}
```

2. **Implement break cost** — `MovementHelper.getMiningDurationTicks()` currently returns `COST_INF`. Replace with actual tool-speed calculation based on the mob's held item (if any).

3. **Implement place cost** — `costOfPlacingAt()` should return a reasonable cost. You'll also need to supply "throwaway" block items for the mob to place.

4. **Add execution logic** — `MobMovementExecutor` needs to actually break/place blocks during path execution. This means:
   - Detecting when the current movement requires a block break
   - Simulating the block-breaking process (or using `level.destroyBlock()`)
   - Placing blocks when needed for bridging

5. **Enable pillar movement** — With block placing, `PILLAR` (straight up) becomes possible. Update `MovementCosts.pillar()` to return a finite cost when `allowPlace` is true.

### Considerations

- Block breaking takes time — the executor needs to wait for the break to complete before advancing
- The mob needs appropriate tools in its inventory for mining speed
- Block placement requires items in inventory
- Server-side block modifications fire events — other mods may react

---

## Per-Mob Configuration

Currently all mobs share the same `CalculationContext` settings. To support per-mob configuration:

### 1. Create a Config Object

```java
public class MobPathfindingConfig {
    public boolean allowParkour = true;
    public boolean allowParkourAscend = true;
    public boolean allowDiagonalDescend = true;
    public boolean allowDiagonalAscend = true;
    public boolean allowBreak = false;
    public boolean allowPlace = false;
    public boolean allowSwim = true;
    public int maxFallHeight = 4;
    public double jumpPenalty = 2.0;
    // ...
}
```

### 2. Store on IManagedMob

Add to the `IManagedMob` interface:

```java
MobPathfindingConfig getConfig();
```

### 3. Pass to CalculationContext

```java
public CalculationContext(IEntityContext entityContext, MobPathfindingConfig config) {
    this.allowParkour = config.allowParkour;
    this.allowBreak = config.allowBreak;
    // ...
}
```

### 4. Apply in ManagedMob.calculatePathAsync()

```java
CalculationContext calcCtx = new CalculationContext(entityContext, this.config);
```

---

## Adding New Commands

Commands are registered in `BaritoneCommands.java` using Brigadier.

### Adding a Command

Add a new `.then()` to the command tree in `register()`:

```java
// /baritone patrol <pos1> <pos2>
.then(Commands.literal("patrol")
    .then(Commands.argument("pos1", BlockPosArgument.blockPos())
        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
            .executes(BaritoneCommands::patrol))))
```

Then implement the handler:

```java
private static int patrol(CommandContext<CommandSourceStack> ctx) {
    BlockPos pos1 = BlockPosArgument.getBlockPos(ctx, "pos1");
    BlockPos pos2 = BlockPosArgument.getBlockPos(ctx, "pos2");

    for (var managed : MobPathfindingManager.INSTANCE.getManagedMobsInternal().values()) {
        // Set up patrol behavior using pathTo + callbacks
        managed.pathTo(pos1);
        managed.setPathCompleteCallback(() -> {
            managed.pathTo(pos2);
            managed.setPathCompleteCallback(() -> {
                managed.pathTo(pos1); // Loop back
            });
        });
    }

    ctx.getSource().sendSuccess(() -> Component.literal("Mobs patrolling"), true);
    return 1;
}
```

### Permission Levels

Commands use `Commands.LEVEL_GAMEMASTERS` (op level 2). To change:

```java
.requires(source -> Commands.LEVEL_ADMINS.check(source.permissions()))  // op level 3
```

---

## Custom Movement Execution

`MobMovementExecutor` translates path positions into mob control inputs. Each tick it:

1. Checks if the mob reached the current waypoint
2. Classifies the movement type (traverse, ascend, parkour, etc.)
3. Calls the appropriate execute method
4. Sets `MoveControl`, `JumpControl`, and sprint state

### Modifying Movement Behavior

The key method is `faceAndMoveToward()`:

```java
private void faceAndMoveToward(Vec3 pos, double tx, double ty, double tz, boolean sprint) {
    mob.setSprinting(sprint);
    mob.getMoveControl().setWantedPosition(tx, ty, tz, speedModifier);
    mob.getLookControl().setLookAt(tx, ty + mob.getEyeHeight(), tz);
}
```

`speedModifier` normalizes fast mobs (e.g. villagers at 0.5 base speed) to a max of 0.3. This prevents overshooting waypoints.

### Sprint Rules

Ported from Baritone's `PathExecutor.shouldSprintNextTick()`:

| Movement | Sprint? | Notes |
|----------|---------|-------|
| Traverse | Yes | With look-ahead (stops before certain transitions) |
| Diagonal | Yes | Always (clear path, no blocks to break) |
| Parkour | Yes | Always — sprint jump boost is essential |
| Descend | Conditional | Only chains (same direction descend/traverse) |
| Ascend | No | Walk + jump |
| Fall | No | Walk forward, gravity handles it |
| Downward | No | Drop straight |
| Swim | No | Jump to swim up |

### Adding a Custom Movement Handler

1. Add to the `MovementType` enum
2. Add detection in `classifyMovement()`
3. Add an `executeMyMovement()` method
4. Add the case to the switch in `tick()`

---

## Extending the API

### Adding Methods to IManagedMob

1. Add the method to `IManagedMob.java`:

```java
/** Pathfind to a position, then execute an action on arrival. */
void pathToAndDo(BlockPos target, Runnable onArrival);
```

2. Implement in `MobPathfindingManager.ManagedMob`:

```java
@Override
public void pathToAndDo(BlockPos target, Runnable onArrival) {
    pathTo(target);
    setPathCompleteCallback(onArrival);
}
```

### Adding Events

Currently there are two callbacks: `pathComplete` and `pathFailed`. To add more:

1. Add the setter to `IManagedMob`
2. Add the field + fire method in `ManagedMob`
3. Call the fire method at the appropriate point in `tick()`

Suggested events to add:
- `onPathCalculated(IPath path)` — when a new path segment is computed
- `onStuck()` — when stuck detection fires (before recalculation)
- `onSegmentComplete()` — when a path segment finishes (before recalc to goal)

### Publishing as a Proper API Artifact

To let other mods depend on just the API (without the implementation):

1. Create a separate source set or subproject for `com.baritoneserver.api`
2. Publish it as a separate Maven artifact (e.g. `baritone-server-api`)
3. Other mods use `modCompileOnly` for the API and `modRuntimeOnly` for the full mod

---

## Key Classes Reference

### Cost Constants (ActionCosts)

| Constant | Value | Description |
|----------|-------|-------------|
| `WALK_ONE_BLOCK_COST` | ~20 ticks | Base walking cost per block |
| `SPRINT_ONE_BLOCK_COST` | ~14.3 ticks | Sprinting cost per block |
| `JUMP_ONE_BLOCK_COST` | ~6.3 ticks | Additional cost for jumping |
| `WALK_ONE_IN_WATER_COST` | ~28.6 ticks | Walking in water |
| `FALL_N_BLOCKS_COST[n]` | varies | Precomputed fall costs by distance |
| `COST_INF` | 1,000,000 | Movement is impossible |

### Block Checking (MovementHelper)

| Method | Returns | Description |
|--------|---------|-------------|
| `canWalkThrough(ctx, x, y, z)` | `boolean` | Block is passable (air, flowers, etc.) |
| `canWalkOn(ctx, x, y, z)` | `boolean` | Block supports standing (solid, slabs, etc.) |
| `avoidWalkingInto(state)` | `boolean` | Block is dangerous (lava, cactus, fire) |
| `isWater(state)` | `boolean` | Water block |
| `isLava(state)` | `boolean` | Lava block |
| `fullyPassable(ctx, x, y, z)` | `boolean` | Passable without doors/ladders/water |

### World Access (ServerBlockStateInterface)

Direct chunk section access — avoids `BlockPos` allocation:

```java
bsi.get0(x, y, z)      // Get BlockState at coordinates
bsi.isLoaded(x, z)     // Check if chunk is loaded
```

Goes directly to `LevelChunkSection.getBlockState(int, int, int)` for maximum performance during A* search.
