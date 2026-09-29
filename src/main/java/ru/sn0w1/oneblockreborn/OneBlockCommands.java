package ru.sn0w1.oneblockreborn;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class OneBlockCommands {
    private OneBlockCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("oneblockreborn")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("resetconfig")
                                .executes(ctx -> {
                                    OneBlockConfig.ApplyResult result = OneBlockConfig.resetToDefaults(ctx.getSource().getServer());
                                    if (result.success()) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), true);
                                        return 1;
                                    }
                                    ctx.getSource().sendFailure(Component.literal("§c" + result.message()));
                                    return 0;
                                }))
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    OneBlockConfig.reload(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§aOne-Block Reborn config reloaded."),
                                            true
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                            OneBlockData.PlayerState state =
                                                    OneBlockData.get(ctx.getSource().getServer()).get(player.getUUID());

                                            if (state == null || !state.initialized()) {
                                                ctx.getSource().sendFailure(Component.literal("У игрока ещё нет One-Block Reborn."));
                                                return 0;
                                            }

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(
                                                            "§e" + player.getName().getString()
                                                                    + " §7| stage=" + state.stage()
                                                                    + ", broken=" + state.brokenBlocks()
                                                                    + ", center=" + state.x() + ","
                                                                    + OneBlockConfig.getIslandY(ctx.getSource().getServer()) + ","
                                                                    + state.z()
                                                    ),
                                                    false
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("stage")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("stage", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                                    int stage = IntegerArgumentType.getInteger(ctx, "stage");

                                                    var server = ctx.getSource().getServer();
                                                    if (!OneBlockConfig.isValidStage(server, stage)) {
                                                        ctx.getSource().sendFailure(Component.literal(
                                                                "§cЭтап " + stage + " не существует или выключен в конфигурации."
                                                        ));
                                                        return 0;
                                                    }

                                                    OneBlockData data = OneBlockData.get(server);
                                                    OneBlockData.PlayerState state = data.getOrCreate(player.getUUID());

                                                    if (!state.initialized()) {
                                                        int index = data.allocateIslandIndex();
                                                        int spacing = OneBlockConfig.getSpacing(server);
                                                        state.initialize(index * spacing, 0);
                                                    }

                                                    // The selected stage must remain selected after the next break.
                                                    // Therefore the progress is moved to this stage's threshold instead
                                                    // of being reset to zero (which would immediately recalculate stage 1).
                                                    state.setStage(stage);
                                                    state.setBrokenBlocksForAdmin(
                                                            OneBlockConfig.getStageRequiredBlocks(server, stage)
                                                    );

                                                    ServerLevel overworld = server.overworld();
                                                    BlockPos center = state.center(OneBlockConfig.getIslandY(server));
                                                    BlockState next = OneBlockConfig.chooseBlock(server, stage);
                                                    OneBlockManager.placeOneBlock(overworld, center, next);
                                                    data.setDirty();

                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§aOne-Block Reborn: этап §f" + stage
                                                                    + " §a(§f" + OneBlockConfig.getStageName(server, stage) + "§a) установлен для §f"
                                                                    + player.getName().getString()
                                                    ), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                            OneBlockData data = OneBlockData.get(ctx.getSource().getServer());

                                            data.reset(player.getUUID());

                                            int index = data.allocateIslandIndex();
                                            int spacing = OneBlockConfig.getSpacing(ctx.getSource().getServer());
                                            OneBlockData.PlayerState state = data.getOrCreate(player.getUUID());
                                            state.initialize(index * spacing, 0);
                                            OneBlockManager.prepareIsland(ctx.getSource().getServer().overworld(), state);

                                            player.teleportTo(
                                                    state.x() + 0.5D,
                                                    OneBlockConfig.getIslandY(ctx.getSource().getServer()) + 1.0D,
                                                    state.z() + 0.5D
                                            );

                                            data.setDirty();
                                            player.sendSystemMessage(Component.literal("§aOne-Block Reborn прогресс сброшен."));
                                            return 1;
                                        })))
        );
    }
}
