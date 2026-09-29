package ru.sn0w1.oneblockreborn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

public final class OneBlockEvents {
    private OneBlockEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel overworld = player.getServer().overworld();

        if (!OneBlockConfig.isEnabled(player.getServer())) return;

        OneBlockData data = OneBlockData.get(player.getServer());
        OneBlockData.PlayerState state = data.getOrCreate(player.getUUID());

        if (!state.initialized()) {
            int index = data.allocateIslandIndex();
            int spacing = OneBlockConfig.getSpacing(player.getServer());
            int x = index * spacing;
            int z = 0;

            state.initialize(x, z);
            data.setDirty();

            OneBlockManager.prepareIsland(overworld, state);
            player.teleportTo(overworld, x + 0.5D,
                    OneBlockConfig.getIslandY(player.getServer()) + 1.0D,
                    z + 0.5D, player.getYRot(), player.getXRot());
        }
    }

    /**
     * The owner of the island keeps the progress. Any other player is allowed
     * to help break that central block. Therefore the state used below is
     * resolved from the block position, not from the player who broke it.
     */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(player.getServer().overworld().dimension())) return;
        if (!OneBlockConfig.isEnabled(player.getServer())) return;

        ServerLevel overworld = player.getServer().overworld();
        OneBlockData data = OneBlockData.get(player.getServer());
        int islandY = OneBlockConfig.getIslandY(player.getServer());

        BlockPos pos = event.getPos();
        UUID ownerUuid = data.findOwnerByCenter(pos, islandY);
        if (ownerUuid == null) return;

        OneBlockData.PlayerState ownerState = data.get(ownerUuid);
        if (ownerState == null || !ownerState.initialized()) return;

        // This is a protected One-Block Reborn. Cancel the vanilla break and perform
        // the One-Block Reborn cycle manually. Helpers are intentionally allowed.
        event.setCanceled(true);

        BlockPos center = ownerState.center(islandY);
        BlockState oldState = overworld.getBlockState(center);
        if (oldState.isAir()) return;

        ItemStack tool = player.getMainHandItem().copy();

        // One Block has no surrounding terrain to catch vanilla item drops.
        // Block.dropResources adds random spread/velocity, so drops can fly off
        // the island and become impossible to collect. Spawn the same drops
        // manually at the center with zero initial velocity instead.
        java.util.List<ItemStack> drops = Block.getDrops(oldState, overworld, center, null, player, tool);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemEntity item = new ItemEntity(
                    overworld,
                    center.getX() + 0.5D,
                    center.getY() + 0.5D,
                    center.getZ() + 0.5D,
                    drop.copy()
            );
            item.setDeltaMovement(0.0D, 0.0D, 0.0D);
            item.setPickUpDelay(0);
            overworld.addFreshEntity(item);
        }

        overworld.levelEvent(2001, center, Block.getId(oldState));
        overworld.setBlock(center, Blocks.AIR.defaultBlockState(), 3);

        int brokenStage = ownerState.stage();
        OneBlockConfig.trySpawnMob(player.getServer(), overworld, center, brokenStage);

        ownerState.addBrokenBlock();
        int oldStage = ownerState.stage();

        int newStage = OneBlockConfig.stageForProgress(ownerState.brokenBlocks());
        if (newStage != oldStage) {
            ownerState.setStage(newStage);
            notifyStageChange(player.getServer(), ownerUuid, center, newStage, player.getGameProfile().getName());
        }

        boolean chestSpawned = OneBlockConfig.trySpawnChest(player.getServer(), overworld, center, ownerState.stage());
        if (!chestSpawned) {
            BlockState next = OneBlockConfig.chooseBlock(player.getServer(), ownerState.stage());
            OneBlockManager.placeOneBlock(overworld, center, next);
        }

        data.setDirty();
    }

    /**
     * Announces a stage change for the owner's One-Block Reborn. The owner always
     * receives the message when online. In addition, every player who is
     * currently within 7 blocks of the center receives the same announcement.
     * This makes cooperative mining feel local without broadcasting unrelated
     * progress to the whole server.
     */
    private static void notifyStageChange(net.minecraft.server.MinecraftServer server,
                                          UUID ownerUuid,
                                          BlockPos center,
                                          int newStage,
                                          String breakerName) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        String ownerName = owner != null ? owner.getGameProfile().getName() : server.getProfileCache()
                .get(ownerUuid)
                .map(profile -> profile.getName())
                .orElse("неизвестный игрок");

        Component ownerMessage = Component.translatable(
                "oneblockreborn.stage_update_owner",
                newStage,
                OneBlockConfig.getStageName(server, newStage),
                breakerName
        );
        Component nearbyMessage = Component.translatable(
                "oneblockreborn.stage_update_nearby",
                ownerName,
                newStage,
                OneBlockConfig.getStageName(server, newStage),
                breakerName
        );

        // The owner receives the explicit "your block was updated" message.
        if (owner != null) {
            owner.sendSystemMessage(ownerMessage);
        }

        // Nearby players get the cooperative-progress notification, but the owner
        // is excluded so they do not receive two messages for the same transition.
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (target.getUUID().equals(ownerUuid)) continue;
            if (target.level() == server.overworld()
                    && target.distanceToSqr(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D) <= 49.0D) {
                target.sendSystemMessage(nearbyMessage);
            }
        }
    }

    /**
     * Safety net for gravity blocks that were already scheduled before the
     * stable-placement fix. If a sand/gravel FallingBlockEntity starts from
     * an active One Block center, cancel the entity and restore its block state.
     */
    @SubscribeEvent
    public static void onFallingBlockJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FallingBlockEntity falling)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(level.getServer().overworld().dimension())) return;
        if (!OneBlockConfig.isEnabled(level.getServer())) return;

        BlockPos startPos = falling.getStartPos();
        int islandY = OneBlockConfig.getIslandY(level.getServer());
        OneBlockData data = OneBlockData.get(level.getServer());
        UUID ownerUuid = data.findOwnerByCenter(startPos, islandY);
        if (ownerUuid == null) return;

        event.setCanceled(true);
        OneBlockManager.placeOneBlock(level, startPos, falling.getBlockState());
    }


    /**
     * Creeper explosions and other non-player destruction can remove the central
     * One Block without going through BlockEvent.BreakEvent. Restore any missing
     * central block on the next server tick. This deliberately checks only air: a
     * chest or a normally placed next block is left untouched.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!OneBlockConfig.isEnabled(server)) return;

        ServerLevel overworld = server.overworld();
        OneBlockData data = OneBlockData.get(server);
        int islandY = OneBlockConfig.getIslandY(server);

        for (UUID ownerUuid : data.uuids()) {
            OneBlockData.PlayerState state = data.get(ownerUuid);
            if (state == null || !state.initialized()) continue;

            BlockPos center = state.center(islandY);
            if (overworld.getBlockState(center).isAir()) {
                BlockState next = OneBlockConfig.chooseBlock(server, state.stage());
                OneBlockManager.placeOneBlock(overworld, center, next);
            }
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel overworld = player.getServer().overworld();
        if (!OneBlockConfig.isEnabled(player.getServer())) return;

        OneBlockData.PlayerState state = OneBlockData.get(player.getServer()).get(player.getUUID());
        if (state == null || !state.initialized()) return;

        player.teleportTo(
                overworld,
                state.x() + 0.5D,
                OneBlockConfig.getIslandY(player.getServer()) + 1.0D,
                state.z() + 0.5D,
                player.getYRot(),
                player.getXRot()
        );
    }
}
