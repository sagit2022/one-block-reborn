package ru.sn0w1.oneblockreborn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-player One-Block Reborn progress.
 *
 * The important rule is that the PlayerState belongs to the owner of the island.
 * Other players may break that owner's central block, but their own progress is
 * never changed by helping on somebody else's island.
 */
public final class OneBlockData extends SavedData {
    private static final String FILE = "oneblockreborn_players";

    private final Map<UUID, PlayerState> players = new HashMap<>();
    private int nextIslandIndex = 0;

    private OneBlockData() {}

    public static OneBlockData load(CompoundTag tag, HolderLookup.Provider provider) {
        OneBlockData data = new OneBlockData();
        data.nextIslandIndex = tag.getInt("next_island_index");

        var list = tag.getList("players", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            UUID uuid = p.getUUID("uuid");

            PlayerState state = new PlayerState();
            state.initialized = p.getBoolean("initialized");
            state.x = p.getInt("x");
            state.z = p.getInt("z");
            state.stage = Math.max(1, p.getInt("stage"));
            state.brokenBlocks = Math.max(0L, p.getLong("broken_blocks"));

            data.players.put(uuid, state);
        }

        return data;
    }

    public static OneBlockData get(MinecraftServer server) {
        return get(server.overworld());
    }

    private static OneBlockData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OneBlockData::new, OneBlockData::load, null),
                FILE
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("next_island_index", nextIslandIndex);

        var list = new net.minecraft.nbt.ListTag();
        for (var entry : players.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.putUUID("uuid", entry.getKey());

            PlayerState s = entry.getValue();
            p.putBoolean("initialized", s.initialized);
            p.putInt("x", s.x);
            p.putInt("z", s.z);
            p.putInt("stage", s.stage);
            p.putLong("broken_blocks", s.brokenBlocks);

            list.add(p);
        }

        tag.put("players", list);
        return tag;
    }

    public PlayerState get(UUID uuid) {
        return players.get(uuid);
    }

    public java.util.Set<UUID> uuids() {
        return java.util.Set.copyOf(players.keySet());
    }

    /**
     * Finds the owner of the central One-Block Reborn at an exact position.
     * This is what allows helpers to break another player's block while the
     * progress remains attached to the owner.
     */
    public UUID findOwnerByCenter(BlockPos pos, int islandY) {
        if (pos.getY() != islandY) return null;

        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState state = entry.getValue();
            if (state.initialized
                    && state.x == pos.getX()
                    && state.z == pos.getZ()) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * Kept for protection checks and compatibility with the previous data API.
     */
    public boolean isAnyCenter(BlockPos pos, UUID except, int islandY, int yTolerance) {
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            if (entry.getKey().equals(except)) continue;

            PlayerState state = entry.getValue();
            if (state.initialized
                    && state.x == pos.getX()
                    && state.z == pos.getZ()
                    && Math.abs(pos.getY() - islandY) <= yTolerance) {
                return true;
            }
        }
        return false;
    }

    public PlayerState getOrCreate(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new PlayerState());
    }

    public int allocateIslandIndex() {
        return nextIslandIndex++;
    }

    public void reset(UUID uuid) {
        players.remove(uuid);
        setDirty();
    }

    public static final class PlayerState {
        private boolean initialized;
        private int x;
        private int z;
        private int stage = 1;
        private long brokenBlocks;

        public boolean initialized() { return initialized; }
        public int x() { return x; }
        public int z() { return z; }
        public int stage() { return stage; }
        public long brokenBlocks() { return brokenBlocks; }
        public BlockPos center(int y) { return new BlockPos(x, y, z); }

        public void initialize(int x, int z) {
            this.initialized = true;
            this.x = x;
            this.z = z;
            this.stage = 1;
            this.brokenBlocks = 0;
        }

        public void addBrokenBlock() { brokenBlocks++; }
        public void setBrokenBlocksForAdmin(long value) { brokenBlocks = Math.max(0L, value); }
        public void setStage(int stage) { this.stage = Math.max(1, stage); }
    }
}
