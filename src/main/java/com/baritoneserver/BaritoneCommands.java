package com.baritoneserver;

import com.baritoneserver.api.utils.BetterBlockPos;
import com.baritoneserver.movement.MobMovementExecutor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import com.baritoneserver.visualization.PathVisualizer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class BaritoneCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("baritone")
            .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))

            // /baritone spawn <entity_type>
            .then(Commands.literal("spawn")
                .then(Commands.argument("entity", IdentifierArgument.id())
                    .executes(BaritoneCommands::spawnMob)))

            // /baritone goto <pos> — all mobs
            .then(Commands.literal("goto")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(BaritoneCommands::gotoAll)))

            // /baritone come — all mobs come to player
            .then(Commands.literal("come")
                .executes(BaritoneCommands::comeAll))

            // /baritone follow <player> — all mobs follow a player
            .then(Commands.literal("follow")
                .then(Commands.argument("target", EntityArgument.entity())
                    .executes(BaritoneCommands::followAll))
                .executes(BaritoneCommands::followSelf))

            // /baritone stop [partial_uuid] — stop one or all
            .then(Commands.literal("stop")
                .then(Commands.argument("mob", StringArgumentType.word())
                    .executes(BaritoneCommands::stopOne))
                .executes(BaritoneCommands::stopAll))

            // /baritone info [partial_uuid] — detailed info
            .then(Commands.literal("info")
                .then(Commands.argument("mob", StringArgumentType.word())
                    .executes(BaritoneCommands::infoOne))
                .executes(BaritoneCommands::infoAll))

            // /baritone list
            .then(Commands.literal("list")
                .executes(BaritoneCommands::listMobs))

            // /baritone remove [partial_uuid] — unregister and kill
            .then(Commands.literal("remove")
                .then(Commands.argument("mob", StringArgumentType.word())
                    .executes(BaritoneCommands::removeOne))
                .executes(BaritoneCommands::removeAll))

            // /baritone viz — toggle path visualization
            .then(Commands.literal("viz")
                .executes(BaritoneCommands::toggleViz))

            // /baritone send <partial_uuid> goto <pos>
            .then(Commands.literal("send")
                .then(Commands.argument("mob", StringArgumentType.word())
                    .then(Commands.literal("goto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(BaritoneCommands::sendGoto)))
                    .then(Commands.literal("follow")
                        .then(Commands.argument("target", EntityArgument.entity())
                            .executes(BaritoneCommands::sendFollow)))
                    .then(Commands.literal("come")
                        .executes(BaritoneCommands::sendCome))
                    .then(Commands.literal("stop")
                        .executes(BaritoneCommands::stopOne))))
        );
    }

    // ===== Spawn =====

    private static int spawnMob(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Identifier entityId = IdentifierArgument.getId(ctx, "entity");

        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
        if (entityType == null) {
            source.sendFailure(Component.literal("Unknown entity type: " + entityId));
            return 0;
        }

        Entity entity = entityType.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            source.sendFailure(Component.literal("Failed to create entity: " + entityId));
            return 0;
        }

        if (!(entity instanceof Mob mob)) {
            entity.discard();
            source.sendFailure(Component.literal("Entity " + entityId + " is not a Mob."));
            return 0;
        }

        mob.snapTo(source.getPosition().x, source.getPosition().y, source.getPosition().z, 0, 0);
        mob.setNoAi(false);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);

        MobPathfindingManager.INSTANCE.registerMob(mob);

        String shortUuid = mob.getUUID().toString().substring(0, 8);
        source.sendSuccess(() -> Component.literal(
                "Spawned " + entityId + " [" + shortUuid + "]"), true);
        return 1;
    }

    // ===== Goto =====

    private static int gotoAll(CommandContext<CommandSourceStack> ctx) {
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        MobPathfindingManager.INSTANCE.commandGotoAll(target);
        int count = MobPathfindingManager.INSTANCE.getManagedMobsInternal().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Sending " + count + " mob(s) to " + target.toShortString()), true);
        return count;
    }

    private static int comeAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos pos = BlockPos.containing(source.getPosition());
        MobPathfindingManager.INSTANCE.commandGotoAll(pos);
        int count = MobPathfindingManager.INSTANCE.getManagedMobsInternal().size();
        source.sendSuccess(() -> Component.literal(
                "Calling " + count + " mob(s) to your position"), true);
        return count;
    }

    // ===== Follow =====

    private static int followAll(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Entity target = EntityArgument.getEntity(ctx, "target");
        MobPathfindingManager.INSTANCE.commandFollowAll(target);
        int count = MobPathfindingManager.INSTANCE.getManagedMobsInternal().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                count + " mob(s) now following " + target.getName().getString()), true);
        return count;
    }

    private static int followSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Entity player = source.getEntity();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        MobPathfindingManager.INSTANCE.commandFollowAll(player);
        int count = MobPathfindingManager.INSTANCE.getManagedMobsInternal().size();
        source.sendSuccess(() -> Component.literal(
                count + " mob(s) now following you"), true);
        return count;
    }

    // ===== Stop =====

    private static int stopAll(CommandContext<CommandSourceStack> ctx) {
        MobPathfindingManager.INSTANCE.stopAll();
        ctx.getSource().sendSuccess(() -> Component.literal("Stopped all managed mobs."), true);
        return 1;
    }

    private static int stopOne(CommandContext<CommandSourceStack> ctx) {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        managed.stop();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Stopped mob [" + managed.getMob().getUUID().toString().substring(0, 8) + "]"), true);
        return 1;
    }

    // ===== Info =====

    private static int infoAll(CommandContext<CommandSourceStack> ctx) {
        Map<UUID, MobPathfindingManager.ManagedMob> mobs = MobPathfindingManager.INSTANCE.getManagedMobsInternal();
        if (mobs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No managed mobs."), false);
            return 0;
        }
        for (var entry : mobs.entrySet()) {
            sendMobInfo(ctx.getSource(), entry.getKey(), entry.getValue(), false);
        }
        return mobs.size();
    }

    private static int infoOne(CommandContext<CommandSourceStack> ctx) {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        sendMobInfo(ctx.getSource(), managed.getMob().getUUID(), managed, true);
        return 1;
    }

    private static void sendMobInfo(CommandSourceStack source, UUID uuid, MobPathfindingManager.ManagedMob managed, boolean detailed) {
        Mob mob = managed.getMob();
        String shortUuid = uuid.toString().substring(0, 8);
        String type = mob.getType().toShortString();
        String pos = String.format("(%.1f, %.1f, %.1f)", mob.getX(), mob.getY(), mob.getZ());
        String status = managed.getStatusString();
        String targetStr = managed.getTarget() != null ? managed.getTarget().toShortString() : "none";

        source.sendSuccess(() -> Component.literal(
                String.format("[%s] %s at %s -> %s | %s", shortUuid, type, pos, targetStr, status)), false);

        if (detailed) {
            MobMovementExecutor exec = managed.getExecutor();
            if (exec != null) {
                BetterBlockPos curr = exec.getCurrentTarget();
                source.sendSuccess(() -> Component.literal(String.format(
                        "  Path: %d/%d | Current target: %s | Movement: %s",
                        exec.getPathIndex(), exec.getPathLength(),
                        curr != null ? curr.toShortString() : "none",
                        exec.getMovementTypeDebug())), false);
            }
            source.sendSuccess(() -> Component.literal(String.format(
                    "  Calcs: %d | Last: %dms (%s) | Speed: %.3f | HP: %.1f",
                    managed.getTotalPathCalcs(),
                    managed.getLastCalcTimeMs(),
                    managed.getLastCalcType(),
                    mob.getSpeed(),
                    mob.getHealth())), false);
            source.sendSuccess(() -> Component.literal(String.format(
                    "  Sprinting: %s | In water: %s | On ground: %s",
                    mob.isSprinting(), mob.isInWater(), mob.onGround())), false);
        }
    }

    // ===== List =====

    private static int listMobs(CommandContext<CommandSourceStack> ctx) {
        return infoAll(ctx);
    }

    // ===== Remove =====

    private static int removeOne(CommandContext<CommandSourceStack> ctx) {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        Mob mob = managed.getMob();
        String shortUuid = mob.getUUID().toString().substring(0, 8);
        managed.stop();
        MobPathfindingManager.INSTANCE.unregisterMob(mob.getUUID());
        mob.discard();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed mob [" + shortUuid + "]"), true);
        return 1;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        var mobs = MobPathfindingManager.INSTANCE.getManagedMobsInternal();
        int count = mobs.size();
        for (var entry : mobs.values()) {
            entry.stop();
            entry.getMob().discard();
        }
        mobs.clear();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + count + " mob(s)"), true);
        return count;
    }

    // ===== Visualization =====

    private static int toggleViz(CommandContext<CommandSourceStack> ctx) {
        boolean newState = !MobPathfindingManager.INSTANCE.isVisualizationEnabled();
        MobPathfindingManager.INSTANCE.setVisualizationEnabled(newState);
        String state = newState ? "ON" : "OFF";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Path visualization: " + state), true);
        return 1;
    }

    // ===== Send (individual mob commands) =====

    private static int sendGoto(CommandContext<CommandSourceStack> ctx) {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        managed.pathTo(target);
        String shortUuid = managed.getMob().getUUID().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Mob [" + shortUuid + "] going to " + target.toShortString()), true);
        return 1;
    }

    private static int sendFollow(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        Entity target = EntityArgument.getEntity(ctx, "target");
        managed.follow(target);
        String shortUuid = managed.getMob().getUUID().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Mob [" + shortUuid + "] now following " + target.getName().getString()), true);
        return 1;
    }

    private static int sendCome(CommandContext<CommandSourceStack> ctx) {
        String partial = StringArgumentType.getString(ctx, "mob");
        MobPathfindingManager.ManagedMob managed = MobPathfindingManager.INSTANCE.findByPartialUuid(partial);
        if (managed == null) {
            ctx.getSource().sendFailure(Component.literal("No mob found matching: " + partial));
            return 0;
        }
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        managed.pathTo(pos);
        String shortUuid = managed.getMob().getUUID().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Mob [" + shortUuid + "] coming to your position"), true);
        return 1;
    }
}
