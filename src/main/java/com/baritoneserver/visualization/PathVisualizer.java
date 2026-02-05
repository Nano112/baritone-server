package com.baritoneserver.visualization;

import com.baritoneserver.BaritoneServerMod;
import com.baritoneserver.api.utils.BetterBlockPos;
import com.mojang.math.Transformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Visualizes calculated paths using Display.BlockDisplay entities.
 * Small translucent glass markers are placed at each path node,
 * visible to nearby players without affecting gameplay.
 */
public class PathVisualizer {
    public static final PathVisualizer INSTANCE = new PathVisualizer();

    private final Map<UUID, List<Display.BlockDisplay>> activeDisplays = new HashMap<>();

    /** Max path nodes to visualize to limit entity count */
    private static final int MAX_DISPLAY_NODES = 100;

    /** Scale of the display markers (0.3 = small cubes) */
    private static final float MARKER_SCALE = 0.3f;

    /** Block to display — light blue stained glass for visibility */
    private static final BlockState MARKER_BLOCK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();

    /** Transformation for scaling markers down */
    private static final Transformation MARKER_TRANSFORM = new Transformation(
            new Vector3f(0.35f, 0.0f, 0.35f), // translate to center the scaled block
            new Quaternionf(),                  // no rotation
            new Vector3f(MARKER_SCALE, MARKER_SCALE, MARKER_SCALE), // scale
            new Quaternionf()                   // no rotation
    );

    private PathVisualizer() {}

    /**
     * Visualize a path by spawning BlockDisplay entities at each node.
     * Clears any previous visualization for this mob first.
     */
    public void visualizePath(ServerLevel world, List<BetterBlockPos> path, UUID mobId) {
        clearVisualization(mobId);

        if (path == null || path.isEmpty()) return;

        List<Display.BlockDisplay> displays = new ArrayList<>();
        int count = Math.min(path.size(), MAX_DISPLAY_NODES);

        for (int i = 0; i < count; i++) {
            BetterBlockPos pos = path.get(i);

            try {
                Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, world);
                display.setPos(pos.x, pos.y, pos.z);
                display.setBlockState(MARKER_BLOCK);
                display.setTransformation(MARKER_TRANSFORM);

                if (world.addFreshEntity(display)) {
                    displays.add(display);
                }
            } catch (Exception e) {
                BaritoneServerMod.LOGGER.warn("Failed to spawn path display at {},{},{}: {}",
                        pos.x, pos.y, pos.z, e.getMessage());
                break;
            }
        }

        if (!displays.isEmpty()) {
            activeDisplays.put(mobId, displays);
            BaritoneServerMod.LOGGER.debug("Spawned {} path display entities for mob {}",
                    displays.size(), mobId.toString().substring(0, 8));
        }
    }

    /** Remove all display entities for a specific mob */
    public void clearVisualization(UUID mobId) {
        List<Display.BlockDisplay> displays = activeDisplays.remove(mobId);
        if (displays != null) {
            for (Display.BlockDisplay display : displays) {
                if (!display.isRemoved()) {
                    display.discard();
                }
            }
        }
    }

    /** Remove all visualizations for all mobs */
    public void clearAll() {
        for (var entry : activeDisplays.entrySet()) {
            for (Display.BlockDisplay display : entry.getValue()) {
                if (!display.isRemoved()) {
                    display.discard();
                }
            }
        }
        activeDisplays.clear();
    }

    /** Get the number of active display entities across all mobs */
    public int getActiveDisplayCount() {
        int count = 0;
        for (var displays : activeDisplays.values()) {
            count += displays.size();
        }
        return count;
    }
}
