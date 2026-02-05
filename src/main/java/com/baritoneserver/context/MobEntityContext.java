package com.baritoneserver.context;

import com.baritoneserver.api.IEntityContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * IEntityContext implementation backed by a specific Mob entity.
 */
public class MobEntityContext implements IEntityContext {

    private final Mob mob;

    public MobEntityContext(Mob mob) {
        this.mob = mob;
    }

    @Override
    public Mob entity() {
        return mob;
    }

    @Override
    public ServerLevel world() {
        return (ServerLevel) mob.level();
    }
}
