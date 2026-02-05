package com.baritoneserver.api;

import com.baritoneserver.api.utils.BetterBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side entity context, replacing Baritone's IPlayerContext.
 * Provides all entity/world references needed by the pathfinding engine.
 */
public interface IEntityContext {

    Mob entity();

    ServerLevel world();

    default BetterBlockPos entityFeet() {
        Mob e = entity();
        return new BetterBlockPos(e.getX(), e.getY(), e.getZ());
    }

    default Vec3 entityFeetAsVec() {
        Mob e = entity();
        return new Vec3(e.getX(), e.getY(), e.getZ());
    }

    default Vec3 entityHead() {
        Mob e = entity();
        return new Vec3(e.getX(), e.getY() + e.getEyeHeight(), e.getZ());
    }

    default Vec3 entityMotion() {
        return entity().getDeltaMovement();
    }
}
