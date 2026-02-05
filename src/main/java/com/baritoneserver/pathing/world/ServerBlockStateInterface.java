package com.baritoneserver.pathing.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Server-side block state access, replacing Baritone's client-side BlockStateInterface.
 * Caches the last-accessed chunk for locality optimization.
 */
public class ServerBlockStateInterface {

    private final ServerLevel world;
    private ChunkAccess prevChunk;
    private int prevChunkX = Integer.MIN_VALUE;
    private int prevChunkZ = Integer.MIN_VALUE;

    public final BetterWorldBorder worldBorder;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public ServerBlockStateInterface(ServerLevel world) {
        this.world = world;
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    }

    public BlockState get0(int x, int y, int z) {
        if (y < world.getMinY() || y >= world.getMinY() + world.getHeight()) {
            return AIR;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        if (prevChunk != null && chunkX == prevChunkX && chunkZ == prevChunkZ) {
            return getFromChunk(prevChunk, x, y, z);
        }

        try {
            ChunkAccess chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                return AIR;
            }

            prevChunk = chunk;
            prevChunkX = chunkX;
            prevChunkZ = chunkZ;
            return getFromChunk(chunk, x, y, z);
        } catch (Exception e) {
            // Safety net for async access: chunk may have been unloaded mid-calculation
            return AIR;
        }
    }

    /**
     * Get block state from chunk without allocating a BlockPos.
     */
    private static BlockState getFromChunk(ChunkAccess chunk, int x, int y, int z) {
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return AIR;
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section.hasOnlyAir()) {
            return AIR;
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    public boolean isLoaded(int x, int z) {
        return world.hasChunk(x >> 4, z >> 4);
    }

    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        return world.hasChunk(blockX >> 4, blockZ >> 4);
    }

    public ServerLevel getWorld() {
        return world;
    }
}
