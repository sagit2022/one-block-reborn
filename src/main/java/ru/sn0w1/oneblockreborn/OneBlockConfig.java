package ru.sn0w1.oneblockreborn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class OneBlockConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "oneblockreborn.json";

    private static boolean loaded;
    private static Path loadedPath;
    private static MinecraftServer loadedServer;
    private static boolean enabled = true;
    private static int spacing = 500;
    private static int islandY = 100;
    private static int platformRadius = 0;
    private static List<Stage> stages = new ArrayList<>();

    private OneBlockConfig() {}

    public static synchronized String readRawJson(MinecraftServer server) {
        Path path = configPath(server);
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) ensureConfigExists(server, path);
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (migrateLegacyMobSchema(root)) {
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(root, writer);
                }
            }
            return GSON.toJson(root);
        } catch (IOException e) {
            return defaultClientJson();
        }
    }

    public static String defaultClientJson() {
        return GSON.toJson(defaultJson());
    }

    /** Restores the complete world configuration to the built-in defaults.
     *  This changes configuration only; player One-Block progress is not reset.
     */
    public static synchronized ApplyResult resetToDefaults(MinecraftServer server) {
        try {
            Path path = configPath(server);
            Files.createDirectories(path.getParent());
            JsonObject defaults = defaultJson();
            Path temp = path.resolveSibling(FILE_NAME + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(defaults, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            loaded = false;
            reload(server);
            return new ApplyResult(true, "Все настройки One-Block Reborn сброшены к стандартным значениям. Прогресс игроков не изменён.");
        } catch (Exception e) {
            return new ApplyResult(false, "Не удалось сбросить настройки: " + safeMessage(e));
        }
    }

    public static synchronized ApplyResult writeAndReload(MinecraftServer server, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            migrateLegacyMobSchema(root);
            migrateLegacyBlockIds(root);
            removeUnsafeBlockEntries(root);
            parseAndValidate(root.toString());
            Path path = configPath(server);
            Files.createDirectories(path.getParent());

            Path temp = path.resolveSibling(FILE_NAME + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            loaded = false;
            reload(server);
            return new ApplyResult(true, "Настройки One-Block Reborn сохранены и применены без перезапуска сервера.");
        } catch (Exception e) {
            return new ApplyResult(false, "Конфигурация отклонена: " + safeMessage(e));
        }
    }

    public static boolean isEnabled(MinecraftServer server) {
        loadIfNeeded(server);
        return enabled;
    }

    public static int getSpacing(MinecraftServer server) {
        loadIfNeeded(server);
        return spacing;
    }

    public static int getIslandY(MinecraftServer server) {
        loadIfNeeded(server);
        return islandY;
    }

    public static int getPlatformRadius(MinecraftServer server) {
        loadIfNeeded(server);
        return platformRadius;
    }

    public static synchronized void reload(MinecraftServer server) {
        Path path = configPath(server);
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) ensureConfigExists(server, path);

            JsonObject root;
            try (Reader reader = Files.newBufferedReader(path)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            boolean migrated = migrateLegacyMobSchema(root);
            migrated |= migrateLegacyBlockIds(root);
            migrated |= migrateWeightsToPercentages(root);
            migrated |= removeUnsafeBlockEntries(root);
            if (migrated) {
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(root, writer);
                }
            }
            parseAndValidate(root.toString());
            apply(root, server);
            loaded = true;
            loadedPath = path;
            loadedServer = server;
        } catch (Exception e) {
            loaded = true;
            loadedPath = path;
            loadedServer = server;
            // Never reduce a broken configuration to a one-block stone stage.
            // Load the complete built-in progression instead, so the One Block
            // continues cycling through real stage block pools.
            JsonObject defaults = defaultJson();
            apply(defaults, server);
            server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[One-Block Reborn] Ошибка world/data/oneblockreborn.json: " + safeMessage(e) + ". Загружена полная стандартная конфигурация; сохранённый файл не удалён."
            ));
        }
    }

    /** Returns the display name used in stage-change notifications.
     *  Standard stages have familiar One-Block Reborn names; custom stage IDs fall
     *  back to a neutral "Stage N" label.
     */
    public static String getStageName(MinecraftServer server, int stageId) {
        loadIfNeeded(server);
        return stages.stream()
                .filter(stage -> stage.id() == stageId)
                .findFirst()
                .map(Stage::name)
                .orElseGet(() -> defaultStageName(stageId));
    }

    private static String defaultStageName(int stageId) {
        return switch (stageId) {
            case 1 -> "The Plains";
            case 2 -> "The Underground";
            case 3 -> "Icy Tundra";
            case 4 -> "Ocean";
            case 5 -> "Jungle Dungeon";
            case 6 -> "Red Desert";
            case 7 -> "The Nether";
            case 8 -> "Idyll";
            case 9 -> "Desolate Land";
            case 10 -> "The End";
            default -> "Stage " + stageId;
        };
    }

    /** Returns true when the configured stage exists and is enabled. */
    public static boolean isValidStage(MinecraftServer server, int stageId) {
        loadIfNeeded(server);
        return stages.stream().anyMatch(stage -> stage.id() == stageId && stage.enabled());
    }

    /** Returns the progress threshold of the configured stage. */
    public static long getStageRequiredBlocks(MinecraftServer server, int stageId) {
        loadIfNeeded(server);
        return stages.stream()
                .filter(stage -> stage.id() == stageId && stage.enabled())
                .mapToLong(Stage::requiredBlocks)
                .findFirst()
                .orElse(0L);
    }

    public static int stageForProgress(long brokenBlocks) {
        Stage best = null;
        for (Stage stage : stages) {
            if (!stage.enabled()) continue;
            if (stage.requiredBlocks() <= brokenBlocks) best = stage;
        }
        return best == null ? firstEnabledStage().id() : best.id();
    }


    public static void trySpawnMob(MinecraftServer server, ServerLevel level, BlockPos center, int stageId) {
        loadIfNeeded(server);
        Stage stage = stages.stream().filter(s -> s.id() == stageId && s.enabled()).findFirst().orElse(null);
        if (stage == null || stage.mobChance() <= 0 || stage.mobs().isEmpty()) return;
        if (ThreadLocalRandom.current().nextInt(100) >= stage.mobChance()) return;

        List<MobEntry> candidates = stage.mobs().stream()
                .filter(m -> m.weight() > 0)
                .toList();
        int roll = ThreadLocalRandom.current().nextInt(100);
        MobEntry selected = null;
        for (MobEntry mob : candidates) {
            roll -= mob.weight();
            if (roll < 0) { selected = mob; break; }
        }
        // Percentages are literal: if the configured percentages do not add up
        // to 100, the unused part simply means that no mob is selected.
        if (selected == null) return;

        EntityType<?> type;
        try {
            type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(selected.mobId())).orElse(null);
        } catch (Exception ignored) {
            return;
        }
        if (type == null) return;
        Entity entity = type.create(level);
        if (entity == null) return;
        entity.moveTo(center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(entity);
    }
    /** Attempts to spawn a custom-loot chest for the given stage. Returns true when a chest was created. */
    public static boolean trySpawnChest(MinecraftServer server, ServerLevel level, BlockPos center, int stageId) {
        loadIfNeeded(server);
        Stage stage = stages.stream().filter(s -> s.id() == stageId && s.enabled()).findFirst().orElse(null);
        if (stage == null || stage.chestChance() <= 0 || stage.loot().isEmpty()) return false;
        if (ThreadLocalRandom.current().nextInt(100) >= stage.chestChance()) return false;

        level.setBlock(center, Blocks.CHEST.defaultBlockState(), 3);
        if (!(level.getBlockEntity(center) instanceof ChestBlockEntity chest)) {
            OneBlockManager.placeOneBlock(level, center, chooseBlock(server, stageId));
            return false;
        }

        List<LootEntry> candidates = stage.loot().stream().filter(l -> l.weight() > 0).toList();
        if (candidates.isEmpty()) return true;

        int rolls = Math.min(6, Math.max(1, candidates.size()));
        java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
        for (int i = 0; i < rolls; i++) {
            int roll = ThreadLocalRandom.current().nextInt(100);
            LootEntry selected = null;
            for (LootEntry loot : candidates) {
                roll -= loot.weight();
                if (roll < 0) { selected = loot; break; }
            }
            if (selected == null) continue;
            net.minecraft.world.item.Item item;
            try {
                item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(selected.itemId())).orElse(null);
            } catch (Exception ignored) {
                continue;
            }
            if (item == null) continue;
            int min = Math.max(1, Math.min(64, selected.minCount()));
            int max = Math.max(min, Math.min(64, selected.maxCount()));
            int count = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            int slot;
            do { slot = ThreadLocalRandom.current().nextInt(27); } while (usedSlots.size() < 27 && !usedSlots.add(slot));
            chest.setItem(slot, new ItemStack(item, count));
        }
        chest.setChanged();
        return true;
    }

    public static BlockState chooseBlock(MinecraftServer server, int stageId) {
        loadIfNeeded(server);

        Stage stage = stages.stream()
                .filter(s -> s.id() == stageId && s.enabled())
                .findFirst()
                .orElse(firstEnabledStage());

        List<BlockEntry> candidates = stage.blocks().stream()
                .filter(BlockEntry::enabled)
                .filter(b -> b.weight() > 0)
                .filter(b -> resolveBlock(b.blockId()) != Blocks.AIR)
                .filter(b -> isSafeOneBlockId(b.blockId()))
                .filter(b -> hasSolidCollision(server, b.blockId()))
                .toList();

        if (candidates.isEmpty()) {
            server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[One-Block Reborn] У этапа " + stage.id() + " нет доступных блоков с положительным весом. Используется stone."
            ));
            return Blocks.STONE.defaultBlockState();
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        for (BlockEntry entry : candidates) {
            roll -= entry.weight();
            if (roll < 0) {
                return resolveBlock(entry.blockId()).defaultBlockState();
            }
        }
        // The remaining percentage is intentionally not normalized. If the
        // configured block percentages add up to less than 100, the safe
        // fallback is stone rather than secretly increasing another block's chance.
        return Blocks.STONE.defaultBlockState();
    }

    private static void loadIfNeeded(MinecraftServer server) {
        Path path = configPath(server);
        if (!loaded || loadedServer != server || !path.equals(loadedPath)) reload(server);
    }

    private static Path configPath(MinecraftServer server) {
        // Keep the configuration with the actual world. This is important for
        // singleplayer/integrated-server worlds: closing and reopening a world
        // must restore exactly that world's One-Block Reborn settings.
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME);
    }

    private static Path legacyConfigPath(MinecraftServer server) {
        return server.getServerDirectory().resolve("config").resolve(FILE_NAME);
    }

    private static JsonObject parseAndValidate(String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw new IllegalArgumentException("корень JSON должен быть объектом");
        JsonObject root = element.getAsJsonObject();

        JsonElement stagesElement = root.get("stages");
        if (stagesElement == null || !stagesElement.isJsonArray() || stagesElement.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("поле stages должно быть непустым массивом");
        }

        int spacingValue = getInt(root, "spacing", 500);
        if (spacingValue < 100 || spacingValue > 30_000_000) {
            throw new IllegalArgumentException("spacing должен быть от 100 до 30000000");
        }

        int radius = getInt(root, "platform_radius", 0);
        if (radius < 0 || radius > 16) {
            throw new IllegalArgumentException("platform_radius должен быть от 0 до 16");
        }

        Set<Integer> ids = new HashSet<>();
        for (JsonElement stageElement : stagesElement.getAsJsonArray()) {
            if (!stageElement.isJsonObject()) throw new IllegalArgumentException("элемент stages должен быть объектом");
            JsonObject stage = stageElement.getAsJsonObject();
            int id = getInt(stage, "id", -1);
            if (id < 1 || !ids.add(id)) throw new IllegalArgumentException("ID этапов должны быть уникальными и >= 1");

            String stageName = getString(stage, "name", defaultStageName(id));
            if (stageName.isBlank()) throw new IllegalArgumentException("Название этапа не может быть пустым");
            if (stageName.length() > 64) throw new IllegalArgumentException("Название этапа не может быть длиннее 64 символов");

            long required = getLong(stage, "required_blocks", 0);
            if (required < 0) throw new IllegalArgumentException("required_blocks не может быть отрицательным");

            int mobChance = getInt(stage, "mob_chance", 0);
            if (mobChance < 0 || mobChance > 100) {
                throw new IllegalArgumentException("mob_chance этапа должен быть от 0 до 100");
            }
            int chestChance = getInt(stage, "chest_chance", 0);
            if (chestChance < 0 || chestChance > 100) {
                throw new IllegalArgumentException("chest_chance этапа должен быть от 0 до 100");
            }
            JsonElement lootElement = stage.get("loot");
            if (lootElement != null) validateLoot(lootElement);
            JsonElement stageMobsElement = stage.get("mobs");
            if (stageMobsElement != null) validateMobs(stageMobsElement);

            JsonElement blocksElement = stage.get("blocks");
            if (blocksElement == null || !blocksElement.isJsonArray()) {
                throw new IllegalArgumentException("у каждого этапа должен быть массив blocks");
            }
            for (JsonElement blockElement : blocksElement.getAsJsonArray()) {
                if (!blockElement.isJsonObject()) throw new IllegalArgumentException("элемент blocks должен быть объектом");
                JsonObject block = blockElement.getAsJsonObject();
                String idString = getString(block, "block", "");
                ResourceLocation idLocation;
                try {
                    idLocation = ResourceLocation.parse(idString);
                } catch (Exception e) {
                    throw new IllegalArgumentException("неверный ID блока: " + idString);
                }
                if (!BuiltInRegistries.BLOCK.containsKey(idLocation)) {
                    throw new IllegalArgumentException("блок не найден в реестре: " + idString);
                }
                int weight = getInt(block, "weight", 0);
                if (weight < 0 || weight > 100) throw new IllegalArgumentException("процент блока должен быть от 0 до 100: " + idString);

            }
        }
        return root;
    }

    private static void apply(JsonObject root, MinecraftServer server) {
        enabled = getBoolean(root, "enabled", true);
        spacing = Math.max(100, getInt(root, "spacing", 500));
        platformRadius = Math.max(0, Math.min(16, getInt(root, "platform_radius", 0)));
        islandY = clampIslandY(server, getInt(root, "island_y", 100));

        List<Stage> loadedStages = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("stages")) {
            JsonObject stage = element.getAsJsonObject();
            List<BlockEntry> blocks = new ArrayList<>();
            for (JsonElement blockElement : stage.getAsJsonArray("blocks")) {
                JsonObject block = blockElement.getAsJsonObject();
                blocks.add(new BlockEntry(
                        getString(block, "block", "minecraft:stone"),
                        Math.max(0, Math.min(100, getInt(block, "weight", 0))),
                        getBoolean(block, "enabled", true)
                ));
            }
            List<MobEntry> mobs = readMobs(stage.get("mobs"));
            List<LootEntry> loot = readLoot(stage.get("loot"));
            int stageId = getInt(stage, "id", loadedStages.size() + 1);
            loadedStages.add(new Stage(
                    stageId,
                    getString(stage, "name", defaultStageName(stageId)),
                    getBoolean(stage, "enabled", true),
                    Math.max(0, getLong(stage, "required_blocks", 0)),
                    Math.max(0, Math.min(100, getInt(stage, "mob_chance", 0))),
                    Math.max(0, Math.min(100, getInt(stage, "chest_chance", 0))),
                    mobs,
                    loot,
                    blocks
            ));
        }
        loadedStages.sort(Comparator.comparingLong(Stage::requiredBlocks));
        if (loadedStages.stream().noneMatch(Stage::enabled)) {
            loadedStages.set(0, defaultStage(1, 0));
        }
        stages = loadedStages;
    }

    private static int clampIslandY(MinecraftServer server, int value) {
        return Math.max(server.overworld().getMinBuildHeight() + 5,
                Math.min(server.overworld().getMaxBuildHeight() - 5, value));
    }

    private static Block resolveBlock(String id) {
        try {
            return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(Blocks.AIR);
        } catch (Exception e) {
            return Blocks.AIR;
        }
    }

    private static Stage firstEnabledStage() {
        return stages.stream().filter(Stage::enabled).findFirst().orElseGet(() -> defaultStage(1, 0));
    }

    private static JsonObject defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty("config_version", 2);
        root.addProperty("enabled", true);
        root.addProperty("spacing", 500);
        root.addProperty("island_y", 100);
        root.addProperty("platform_radius", 0);

        JsonArray stages = new JsonArray();

        // The default progression is inspired by the classic One-Block Reborn phase
        // structure: 10 themed phases, ending in The End. Block weights here
        // are intentionally simple defaults and can be changed in the GUI.
        stages.add(defaultStageJson(1, 0, 3,
                new String[]{
                        "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
                        "minecraft:sand", "minecraft:gravel", "minecraft:clay",
                        "minecraft:oak_log", "minecraft:birch_log"
                },
                new String[]{"minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken", "minecraft:zombie"}));

        stages.add(defaultStageJson(2, 650, 4,
                new String[]{
                        "minecraft:cobblestone", "minecraft:gravel", "minecraft:sand",
                        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:deepslate",
                        "minecraft:copper_ore", "minecraft:spruce_log", "minecraft:oak_log",
                        "minecraft:birch_log", "minecraft:torch"
                },
                new String[]{"minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper"}));

        stages.add(defaultStageJson(3, 1300, 4,
                new String[]{
                        "minecraft:snow_block", "minecraft:ice",
                        "minecraft:packed_ice", "minecraft:spruce_log", "minecraft:gravel",
                        "minecraft:stone", "minecraft:coal_ore",
                        "minecraft:iron_ore", 
                },
                new String[]{"minecraft:stray", "minecraft:snow_golem", "minecraft:wolf", "minecraft:polar_bear"}));

        stages.add(defaultStageJson(4, 1950, 5,
                new String[]{
                        "minecraft:sand", "minecraft:gravel", "minecraft:clay",
                        "minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine",
                        "minecraft:sea_lantern",                         "minecraft:gold_ore"
                },
                new String[]{"minecraft:drowned", "minecraft:cod", "minecraft:salmon", "minecraft:squid", "minecraft:guardian"}));

        stages.add(defaultStageJson(5, 2600, 6,
                new String[]{
                        "minecraft:jungle_log", "minecraft:dark_oak_log",
                        "minecraft:moss_block", "minecraft:podzol", "minecraft:dirt",
                        "minecraft:gravel", "minecraft:clay", "minecraft:melon", "minecraft:pumpkin"
                },
                new String[]{"minecraft:parrot", "minecraft:panda", "minecraft:ocelot", "minecraft:spider", "minecraft:witch"}));

        stages.add(defaultStageJson(6, 3250, 6,
                new String[]{
                        "minecraft:red_sand", "minecraft:red_sandstone", "minecraft:terracotta",
                        "minecraft:orange_terracotta", "minecraft:red_terracotta", "minecraft:yellow_terracotta",
                        "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:cactus",
                        "minecraft:sand", "minecraft:gravel", "minecraft:gold_ore"
                },
                new String[]{"minecraft:husk", "minecraft:rabbit", "minecraft:spider", "minecraft:creeper", "minecraft:witch"}));

        stages.add(defaultStageJson(7, 3900, 7,
                new String[]{
                        "minecraft:netherrack", "minecraft:soul_sand", "minecraft:soul_soil",
                        "minecraft:basalt", "minecraft:blackstone", "minecraft:nether_bricks",
                        "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore", "minecraft:glowstone",
                        "minecraft:magma_block", "minecraft:warped_nylium",
                        "minecraft:crimson_stem", "minecraft:warped_stem"
                },
                new String[]{"minecraft:zombified_piglin", "minecraft:piglin", "minecraft:hoglin", "minecraft:ghast", "minecraft:blaze", "minecraft:magma_cube", "minecraft:wither_skeleton"}));

        stages.add(defaultStageJson(8, 4550, 6,
                new String[]{
                        "minecraft:amethyst_block", "minecraft:calcite", "minecraft:tuff",
                        "minecraft:dripstone_block", "minecraft:moss_block", "minecraft:mud",
                        "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:birch_log",
                        "minecraft:sand", "minecraft:gravel", "minecraft:clay",
                },
                new String[]{"minecraft:axolotl", "minecraft:frog", "minecraft:bee", "minecraft:goat", "minecraft:slime"}));

        stages.add(defaultStageJson(9, 5200, 8,
                new String[]{
                        "minecraft:deepslate", "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore",
                        "minecraft:deepslate_gold_ore", "minecraft:deepslate_diamond_ore", "minecraft:redstone_ore",
                        "minecraft:lapis_ore", "minecraft:emerald_ore", "minecraft:obsidian",
                        "minecraft:sculk", "minecraft:sculk_catalyst", "minecraft:sculk_sensor",
                        "minecraft:ancient_debris"
                },
                new String[]{"minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:enderman", "minecraft:cave_spider"}));

        stages.add(defaultStageJson(10, 5850, 10,
                new String[]{
                        "minecraft:end_stone", "minecraft:end_stone_bricks", "minecraft:purpur_block",
                        "minecraft:purpur_pillar", "minecraft:chorus_flower",
                        "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest",
                        "minecraft:diamond_block", "minecraft:emerald_block"
                },
                new String[]{"minecraft:enderman", "minecraft:shulker", "minecraft:endermite"}));

        root.add("stages", stages);
        return root;
    }

    private static void createDefault(Path path) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(defaultJson(), writer);
        }
    }

    private static JsonObject defaultStageJson(int id, long required, int mobChance, String[] blocks, String[] mobs) {
        JsonObject stage = new JsonObject();
        stage.addProperty("id", id);
        stage.addProperty("name", defaultStageName(id));
        stage.addProperty("enabled", true);
        stage.addProperty("required_blocks", required);
        stage.addProperty("mob_chance", mobChance);
        stage.addProperty("chest_chance", 0); // Disabled by default; configure per stage in the GUI.
        stage.add("loot", new JsonArray());

        JsonArray mobList = new JsonArray();
        int mobBase = mobs.length == 0 ? 0 : 100 / mobs.length;
        int mobRemainder = mobs.length == 0 ? 0 : 100 % mobs.length;
        for (int i = 0; i < mobs.length; i++) {
            JsonObject mob = new JsonObject();
            mob.addProperty("mob", mobs[i]);
            mob.addProperty("weight", mobBase + (i < mobRemainder ? 1 : 0));
            mobList.add(mob);
        }
        stage.add("mobs", mobList);

        JsonArray list = new JsonArray();
        int blockBase = blocks.length == 0 ? 0 : 100 / blocks.length;
        int blockRemainder = blocks.length == 0 ? 0 : 100 % blocks.length;
        for (int i = 0; i < blocks.length; i++) {
            JsonObject block = new JsonObject();
            block.addProperty("block", blocks[i]);
            block.addProperty("enabled", true);
            block.addProperty("weight", blockBase + (i < blockRemainder ? 1 : 0));
            list.add(block);
        }
        stage.add("blocks", list);
        return stage;
    }

    private static Stage defaultStage(int id, long required) {
        return new Stage(id, defaultStageName(id), true, required, 0, 0, List.of(), List.of(), List.of(new BlockEntry("minecraft:stone", 1, true)));
    }

    private static void validateLoot(JsonElement lootElement) {
        if (!lootElement.isJsonArray()) throw new IllegalArgumentException("loot должен быть массивом");
        for (JsonElement lootElementEntry : lootElement.getAsJsonArray()) {
            if (!lootElementEntry.isJsonObject()) throw new IllegalArgumentException("элемент loot должен быть объектом");
            JsonObject loot = lootElementEntry.getAsJsonObject();
            String itemId = getString(loot, "item", "");
            ResourceLocation itemLocation;
            try { itemLocation = ResourceLocation.parse(itemId); }
            catch (Exception e) { throw new IllegalArgumentException("неверный ID предмета: " + itemId); }
            if (!BuiltInRegistries.ITEM.containsKey(itemLocation)) throw new IllegalArgumentException("предмет не найден в реестре: " + itemId);
            int weight = getInt(loot, "weight", 0);
            int min = getInt(loot, "min_count", 1);
            int max = getInt(loot, "max_count", min);
            if (weight < 0 || weight > 100) throw new IllegalArgumentException("процент лута должен быть от 0 до 100: " + itemId);
            if (min < 1 || min > 64 || max < min || max > 64) throw new IllegalArgumentException("количество лута должно быть от 1 до 64: " + itemId);
        }
    }

    private static List<LootEntry> readLoot(JsonElement lootElement) {
        List<LootEntry> result = new ArrayList<>();
        if (lootElement == null || !lootElement.isJsonArray()) return result;
        for (JsonElement element : lootElement.getAsJsonArray()) {
            JsonObject loot = element.getAsJsonObject();
            int min = Math.max(1, Math.min(64, getInt(loot, "min_count", 1)));
            int max = Math.max(min, Math.min(64, getInt(loot, "max_count", min)));
            result.add(new LootEntry(getString(loot, "item", "minecraft:bread"), Math.max(0, Math.min(100, getInt(loot, "weight", 0))), min, max));
        }
        return result;
    }

    private static void validateMobs(JsonElement mobsElement) {
        if (!mobsElement.isJsonArray()) throw new IllegalArgumentException("mobs должен быть массивом");
        for (JsonElement mobElement : mobsElement.getAsJsonArray()) {
            if (!mobElement.isJsonObject()) throw new IllegalArgumentException("элемент mobs должен быть объектом");
            JsonObject mob = mobElement.getAsJsonObject();
            String mobId = getString(mob, "mob", "");
            ResourceLocation mobLocation;
            try {
                mobLocation = ResourceLocation.parse(mobId);
            } catch (Exception e) {
                throw new IllegalArgumentException("неверный ID моба: " + mobId);
            }
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(mobLocation)) {
                throw new IllegalArgumentException("моб не найден в реестре: " + mobId);
            }
            int mobWeight = getInt(mob, "weight", 0);
            if (mobWeight < 0 || mobWeight > 100) throw new IllegalArgumentException("процент моба должен быть от 0 до 100: " + mobId);
        }
    }

    private static List<MobEntry> readMobs(JsonElement mobsElement) {
        List<MobEntry> result = new ArrayList<>();
        if (mobsElement == null || !mobsElement.isJsonArray()) return result;
        for (JsonElement mobElement : mobsElement.getAsJsonArray()) {
            JsonObject mob = mobElement.getAsJsonObject();
            result.add(new MobEntry(
                    getString(mob, "mob", "minecraft:zombie"),
                    Math.max(0, Math.min(100, getInt(mob, "weight", 0)))
            ));
        }
        return result;
    }

    /** Converts the old relative weights into literal percentages once. */
    private static boolean migrateWeightsToPercentages(JsonObject root) {
        int version = getInt(root, "config_version", 1);
        if (version >= 2) return false;
        JsonElement stagesElement = root.get("stages");
        if (stagesElement != null && stagesElement.isJsonArray()) {
            for (JsonElement stageElement : stagesElement.getAsJsonArray()) {
                if (!stageElement.isJsonObject()) continue;
                JsonObject stage = stageElement.getAsJsonObject();
                convertArrayToPercentages(stage.get("blocks"));
                convertArrayToPercentages(stage.get("mobs"));
                convertArrayToPercentages(stage.get("loot"));
            }
        }
        root.addProperty("config_version", 2);
        return true;
    }

    private static void convertArrayToPercentages(JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        long total = 0L;
        for (JsonElement e : array) {
            if (e.isJsonObject()) total += Math.max(0, getInt(e.getAsJsonObject(), "weight", 0));
        }
        if (total <= 0) return;
        int assigned = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonElement e = array.get(i);
            if (!e.isJsonObject()) continue;
            int old = Math.max(0, getInt(e.getAsJsonObject(), "weight", 0));
            int percent = (int) ((old * 100L) / total);
            if (i == array.size() - 1) percent = Math.max(0, 100 - assigned);
            percent = Math.max(0, Math.min(100, percent));
            e.getAsJsonObject().addProperty("weight", percent);
            assigned += percent;
        }
    }

    /**
     * Removes blocks that cannot safely be used as the single One Block because
     * they are plants, foliage, thin decorative blocks, or other blocks the
     * player can fall through / cannot reliably stand on.
     */
    private static boolean removeUnsafeBlockEntries(JsonObject root) {
        boolean changed = false;
        JsonElement stagesElement = root.get("stages");
        if (stagesElement == null || !stagesElement.isJsonArray()) return false;

        for (JsonElement stageElement : stagesElement.getAsJsonArray()) {
            if (!stageElement.isJsonObject()) continue;
            JsonObject stage = stageElement.getAsJsonObject();
            JsonArray blocks = stage.getAsJsonArray("blocks");
            if (blocks == null) continue;

            java.util.Iterator<JsonElement> iterator = blocks.iterator();
            while (iterator.hasNext()) {
                JsonElement element = iterator.next();
                if (!element.isJsonObject()) continue;
                String id = getString(element.getAsJsonObject(), "block", "");
                if (!isSafeOneBlockId(id)) {
                    iterator.remove();
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * A One Block must have a real collision shape so a player can never fall
     * through the generated block. This catches every collision-less block,
     * including blocks not known to the hard-coded safety list (water, fire,
     * torches, flowers, redstone dust, tripwire, etc.).
     */
    private static boolean hasSolidCollision(MinecraftServer server, String id) {
        Block block = resolveBlock(id);
        if (block == Blocks.AIR) return false;
        try {
            ServerLevel level = server.overworld();
            BlockState state = block.defaultBlockState();
            return !state.getCollisionShape(level, BlockPos.ZERO).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSafeOneBlockId(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);

        // Plants, crops, foliage, vines and thin decorative blocks.
        String[] exactUnsafe = {
                "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
                "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots",
                "minecraft:nether_wart", "minecraft:sweet_berry_bush", "minecraft:dead_bush",
                "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
                "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
                "minecraft:mangrove_propagule", "minecraft:cherry_sapling",
                "minecraft:vine", "minecraft:weeping_vines", "minecraft:twisting_vines",
                "minecraft:cave_vines", "minecraft:glow_lichen", "minecraft:kelp", "minecraft:kelp_plant",
                "minecraft:tall_seagrass", "minecraft:bamboo", "minecraft:bamboo_sapling",
                "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts",
                "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:azalea",
                "minecraft:flowering_azalea", "minecraft:snow",
                "minecraft:powder_snow", "minecraft:torchflower", "minecraft:pitcher_plant",
                "minecraft:torchflower_crop", "minecraft:pitcher_crop", "minecraft:chorus_plant",
                "minecraft:chorus_flower"
        };
        for (String unsafe : exactUnsafe) if (normalized.equals(unsafe)) return false;

        // Covers all vanilla leaf blocks and common thin plant/flower blocks.
        if (normalized.endsWith("_leaves") || normalized.endsWith("_sapling")) return false;
        if (normalized.contains(":carpet") || normalized.contains(":flower") || normalized.contains(":tulip")) return false;
        if (normalized.contains(":grass") || normalized.contains(":fern")) return false;
        return true;
    }

    /** Migrates block IDs that existed in older project defaults/configs. */
    private static boolean migrateLegacyBlockIds(JsonObject root) {
        boolean changed = false;
        JsonArray stagesArray = root.getAsJsonArray("stages");
        if (stagesArray == null) return false;

        // Older versions accidentally wrote several item/old IDs into the block
        // list.  A single invalid entry used to make the whole configuration
        // fail validation and the runtime then fell back to one stone block.
        // Keep old worlds usable by fixing every known legacy ID here.
        java.util.Map<String, String> replacements = java.util.Map.of(
                "minecraft:grass", "minecraft:short_grass",
                "minecraft:quartz_ore", "minecraft:nether_quartz_ore",
                "minecraft:glow_berries", "minecraft:cave_vines"
        );

        for (JsonElement stageElement : stagesArray) {
            if (!stageElement.isJsonObject()) continue;
            JsonObject stage = stageElement.getAsJsonObject();
            JsonArray blocks = stage.getAsJsonArray("blocks");
            if (blocks == null) continue;
            for (JsonElement blockElement : blocks) {
                if (!blockElement.isJsonObject()) continue;
                JsonObject block = blockElement.getAsJsonObject();
                String oldId = getString(block, "block", "");
                String newId = replacements.get(oldId);
                if (newId != null) {
                    block.addProperty("block", newId);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean migrateLegacyMobSchema(JsonObject root) {
        boolean changed = false;
        JsonElement stagesElement = root.get("stages");
        if (stagesElement == null || !stagesElement.isJsonArray()) return false;

        for (JsonElement stageElement : stagesElement.getAsJsonArray()) {
            if (!stageElement.isJsonObject()) continue;
            JsonObject stage = stageElement.getAsJsonObject();
            int stageId = getInt(stage, "id", 1);
            if (!stage.has("name") || !stage.get("name").isJsonPrimitive() || stage.get("name").getAsString().isBlank()) {
                stage.addProperty("name", defaultStageName(stageId));
                changed = true;
            }
            if (!stage.has("chest_chance")) {
                stage.addProperty("chest_chance", 0);
                changed = true;
            }
            if (!stage.has("loot") || !stage.get("loot").isJsonArray()) { stage.add("loot", new JsonArray()); changed = true; }

            if (!stage.has("mob_chance")) {
                int bestChance = 0;
                JsonArray collectedMobs = new JsonArray();
                JsonElement blocksElement = stage.get("blocks");
                if (blocksElement != null && blocksElement.isJsonArray()) {
                    for (JsonElement blockElement : blocksElement.getAsJsonArray()) {
                        if (!blockElement.isJsonObject()) continue;
                        JsonObject block = blockElement.getAsJsonObject();
                        bestChance = Math.max(bestChance, getInt(block, "mob_chance", 0));
                        JsonElement blockMobs = block.get("mobs");
                        if (blockMobs != null && blockMobs.isJsonArray()) {
                            for (JsonElement mob : blockMobs.getAsJsonArray()) collectedMobs.add(mob.deepCopy());
                        }
                    }
                }
                stage.addProperty("mob_chance", bestChance);
                stage.add("mobs", collectedMobs);
                changed = true;
            }

            JsonElement blocksElement = stage.get("blocks");
            if (blocksElement != null && blocksElement.isJsonArray()) {
                for (JsonElement blockElement : blocksElement.getAsJsonArray()) {
                    if (!blockElement.isJsonObject()) continue;
                    JsonObject block = blockElement.getAsJsonObject();
                    if (block.has("mob_chance")) { block.remove("mob_chance"); changed = true; }
                    if (block.has("mobs")) { block.remove("mobs"); changed = true; }
                }
            }
        }
        return changed;
    }

    private static void ensureConfigExists(MinecraftServer server, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) return;

        // Migrate the old global config once, so an existing setup is not lost.
        Path legacy = legacyConfigPath(server);
        if (Files.exists(legacy)) {
            Files.copy(legacy, path, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        createDefault(path);
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : fallback;
    }

    private static long getLong(JsonObject object, String key, long fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : fallback;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public record ApplyResult(boolean success, String message) {}
    private record Stage(int id, String name, boolean enabled, long requiredBlocks, int mobChance, int chestChance, List<MobEntry> mobs, List<LootEntry> loot, List<BlockEntry> blocks) {}
    private record BlockEntry(String blockId, int weight, boolean enabled) {}
    private record MobEntry(String mobId, int weight) {}
    private record LootEntry(String itemId, int weight, int minCount, int maxCount) {}
}
