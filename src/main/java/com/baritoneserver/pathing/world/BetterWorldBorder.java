package com.baritoneserver.pathing.world;

import net.minecraft.world.level.border.WorldBorder;

/**
 * Cached world border check, ported from Baritone.
 */
public class BetterWorldBorder {

    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    public BetterWorldBorder(WorldBorder border) {
        this.minX = border.getMinX();
        this.maxX = border.getMaxX();
        this.minZ = border.getMinZ();
        this.maxZ = border.getMaxZ();
    }

    public boolean canPlaceAt(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }
}
