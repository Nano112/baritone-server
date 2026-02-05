# Baritone Server

Server-side Fabric mod that ports [Baritone's](https://github.com/cabaletta/baritone) A* pathfinding engine to control mobs. Mobs navigate the world using the same pathfinding logic Baritone uses for players — traversing, jumping, sprinting, parkour, swimming, and falling — all calculated asynchronously off the main server thread.

## Features

- **A\* Pathfinding** — Full port of Baritone's pathfinding with 22 movement directions (traverse, ascend, descend, diagonal, parkour, fall, swim, downward)
- **Async Calculation** — Path calculations run on a dedicated thread with configurable timeouts, never blocking the server tick
- **Faithful Movement** — Sprint control, parkour jumps (up to 4 blocks + ascend), diagonal movement, and descend chains ported from Baritone's actual logic
- **9 Goal Types** — `GoalBlock`, `GoalNear`, `GoalXZ`, `GoalYLevel`, `GoalGetToBlock`, `GoalTwoBlocks`, `GoalRunAway`, `GoalComposite`, `GoalInverted`
- **Follow Mode** — Mobs follow entities with configurable stop distance and periodic recalculation
- **Works with All Mobs** — Zombies, skeletons, villagers, piglins — vanilla AI (both GoalSelector and Brain) is disabled cleanly
- **Path Visualization** — Debug display using block display entities, toggled with `/baritone viz`
- **Public API** — Other mods can register mobs, send pathfinding commands, use custom goals, and receive callbacks

## Requirements

- Minecraft 1.21.11
- Fabric Loader >= 0.18.2
- Fabric API
- Java 21

## Commands

All commands require operator permissions.

| Command | Description |
|---------|-------------|
| `/baritone spawn <entity_type>` | Spawn a mob under Baritone control |
| `/baritone goto <x> <y> <z>` | Send all mobs to a position |
| `/baritone come` | Call all mobs to your position |
| `/baritone follow [target]` | All mobs follow a target (default: you) |
| `/baritone stop [uuid]` | Stop one or all mobs |
| `/baritone send <uuid> goto <pos>` | Send a specific mob to a position |
| `/baritone send <uuid> follow <target>` | A specific mob follows a target |
| `/baritone send <uuid> come` | Call a specific mob to you |
| `/baritone send <uuid> stop` | Stop a specific mob |
| `/baritone info [uuid]` | Show status of one or all mobs |
| `/baritone list` | List all managed mobs |
| `/baritone remove [uuid]` | Unregister and kill one or all mobs |
| `/baritone viz` | Toggle path visualization |

UUIDs can be partial (first few characters).

## API Usage

Other Fabric mods can depend on Baritone Server to pathfind mobs programmatically.

### Setup

Add the mod JAR as a dependency in your `build.gradle`:

```groovy
dependencies {
    modImplementation files("libs/baritone-server-0.1.0.jar")
}
```

Add it to your `fabric.mod.json`:

```json
{
  "depends": {
    "baritone-server": ">=0.1.0"
  }
}
```

### Basic Usage

```java
import com.baritoneserver.api.BaritoneAPI;
import com.baritoneserver.api.IManagedMob;
import com.baritoneserver.api.IMobPathfindingManager;

// Get the manager
IMobPathfindingManager manager = BaritoneAPI.getMobManager();

// Register a mob — this disables its vanilla AI
IManagedMob managed = manager.registerMob(myZombie);

// Pathfind to a position
managed.pathTo(new BlockPos(100, 64, 200));

// Follow a player, stopping within 3 blocks
managed.follow(player, 3.0);

// Stop movement
managed.stop();

// Unregister when done
manager.unregisterMob(myZombie.getUUID());
```

### Custom Goals

Use any of the 9 built-in goal types for flexible pathfinding:

```java
import com.baritoneserver.api.pathing.goals.*;

// Get within 5 blocks of a position
managed.pathTo(new GoalNear(new BlockPos(100, 64, 200), 5));

// Go to specific X/Z coordinates (any Y level)
managed.pathTo(new GoalXZ(100, 200));

// Reach a specific Y level
managed.pathTo(new GoalYLevel(64));

// Run away from a position (at least 20 blocks)
managed.pathTo(new GoalRunAway(20, new BlockPos(100, 64, 200)));

// Combine multiple goals
managed.pathTo(new GoalComposite(
    new GoalNear(posA, 3),
    new GoalNear(posB, 3)
));

// Invert a goal — go anywhere EXCEPT this area
managed.pathTo(new GoalInverted(new GoalNear(dangerZone, 10)));
```

### Callbacks

Get notified when pathfinding completes or fails:

```java
managed.setPathCompleteCallback(() -> {
    System.out.println("Mob arrived at destination!");
});

managed.setPathFailedCallback(() -> {
    System.out.println("Mob couldn't find a path.");
});
```

### Querying State

```java
managed.isActive();          // true if pathfinding or following
managed.getStatusString();   // "idle", "calculating...", "moving 5/23 [TRAVERSE]", etc.
managed.getTarget();         // current BlockPos target, or null
managed.getFollowTarget();   // entity being followed, or null
managed.getPathIndex();      // current position in path
managed.getPathLength();     // total path waypoints
managed.getCurrentPath();    // List<BetterBlockPos> of the current path
managed.getTotalPathCalcs(); // total A* calculations performed
managed.getLastCalcTimeMs(); // duration of last calculation in ms
```

### API Reference

| Interface | Key Methods |
|-----------|-------------|
| `BaritoneAPI` | `getMobManager()` |
| `IMobPathfindingManager` | `registerMob(Mob)`, `unregisterMob(UUID)`, `getManagedMob(UUID)`, `getManagedMobs()`, `stopAll()` |
| `IManagedMob` | `pathTo(BlockPos)`, `pathTo(Goal)`, `follow(Entity)`, `follow(Entity, double)`, `stop()`, `setPathCompleteCallback(Runnable)`, `setPathFailedCallback(Runnable)` |

## Building

```bash
git clone https://github.com/Nano112/baritone-server.git
cd baritone-server
./gradlew build
```

The mod JAR will be in `build/libs/`.

## Architecture

- **A\* Engine** — `AStarPathFinder`, `BinaryHeapOpenSet`, `PathNode` — faithful port of Baritone's search algorithm
- **Movement Costs** — `MovementHelper`, `MovementCosts`, `Moves` — 22 movement directions with accurate cost calculation
- **World Access** — `ServerBlockStateInterface` reads directly from `LevelChunkSection` to avoid `BlockPos` allocation overhead
- **Movement Execution** — `MobMovementExecutor` drives mobs via `MoveControl` + `JumpControl` with sprint control ported from Baritone's `PathExecutor`
- **Async Calculation** — Single-thread `ExecutorService` with `CompletableFuture`, context built on main thread, result polled each tick
- **Speed Normalization** — Fast mobs (villagers at 0.5 base speed) are capped to prevent overshooting waypoints

## License

LGPL-3.0 — same as Baritone.
