package ru.sn0w1.oneblockreborn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class OneBlockManager {
    private OneBlockManager() {}

    public static void prepareIsland(ServerLevel level, OneBlockData.PlayerState state) {
        int y = OneBlockConfig.getIslandY(level.getServer());
        BlockPos center = new BlockPos(state.x(), y, state.z());

        int radius = OneBlockConfig.getPlatformRadius(level.getServer());

        // Classic One-Block Reborn uses no stone platform when platform_radius <= 0.
        if (radius > 0) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, -1, dz);
                    if (level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }

        // The center is the only normal One-Block Reborn that the player breaks.
        if (level.getBlockState(center).isAir()) {
            placeOneBlock(level, center, OneBlockConfig.chooseBlock(level.getServer(), state.stage()));
        }

    }

    /**
     * Places the central One Block while keeping gravity blocks (sand, gravel,
     * concrete powder, etc.) fixed in the void. Vanilla FallingBlock blocks
     * schedule a block tick after placement; clearing that one-position tick
     * prevents them from turning into FallingBlockEntity.
     */
    public static void placeOneBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
        if (state.getBlock() instanceof FallingBlock) {
            level.getBlockTicks().clearArea(new BoundingBox(pos));
        }
    }
}
