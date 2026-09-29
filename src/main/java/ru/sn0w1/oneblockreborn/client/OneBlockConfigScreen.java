package ru.sn0w1.oneblockreborn.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.sn0w1.oneblockreborn.OneBlockNetwork;

/** In-game OP-only editor for the world configuration. */
public final class OneBlockConfigScreen extends Screen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private enum Page { MAIN, GENERAL, STAGES, STAGE, BLOCK, MOBS, MOB, CHEST, LOOT, LOOT_ENTRY }

    private final Screen parent;
    private Page page = Page.MAIN;
    private JsonObject root;
    private int stageIndex = -1;
    private int blockIndex = -1;
    private int mobIndex = -1;
    private int lootIndex = -1;

    private EditBox spacingBox;
    private EditBox islandYBox;
    private EditBox radiusBox;
    private EditBox stageNameBox;
    private EditBox requiredBox;
    private EditBox stageMobChanceBox;
    private EditBox blockIdBox;
    private EditBox blockWeightBox;
    private EditBox mobIdBox;
    private EditBox mobWeightBox;
    private EditBox chestChanceBox;
    private EditBox lootItemBox;
    private EditBox lootWeightBox;
    private EditBox lootMinBox;
    private EditBox lootMaxBox;
    private Button saveButton;
    private boolean saveInFlight;
    private Page returnPageAfterSave;
    private Component status = Component.translatable("oneblockreborn.status.connecting");

    public OneBlockConfigScreen(Screen parent) {
        super(Component.translatable("oneblockreborn.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        buildPage();
    }

    private void buildPage() {
        clearWidgets();
        spacingBox = islandYBox = radiusBox = null;
        stageNameBox = requiredBox = stageMobChanceBox = null;
        blockIdBox = blockWeightBox = null;
        mobIdBox = mobWeightBox = null;
        chestChanceBox = lootItemBox = lootWeightBox = lootMinBox = lootMaxBox = null;
        saveButton = null;

        if (root == null) {
            addButton(t("load_config"), this.width / 2 - 100, 90, 200, this::request);
            addButton(t("close"), this.width / 2 - 100, 120, 200, this::onClose);
            return;
        }

        switch (page) {
            case MAIN -> buildMain();
            case GENERAL -> buildGeneral();
            case STAGES -> buildStages();
            case STAGE -> buildStage();
            case BLOCK -> buildBlock();
            case MOBS -> buildMobs();
            case MOB -> buildMob();
            case CHEST -> buildChest();
            case LOOT -> buildLoot();
            case LOOT_ENTRY -> buildLootEntry();
        }
    }

    private void buildMain() {
        int x = this.width / 2 - 150;
        int y = 52;
        addButton(t("general"), x, y, 300, () -> { page = Page.GENERAL; buildPage(); });
        addButton(t("stages"), x, y + 28, 300, () -> { page = Page.STAGES; buildPage(); });
        addButton(t("reload_from_server"), x, y + 56, 300, this::request);
        saveButton = addButton(t("save_apply"), x, y + 84, 300, this::save);
        addButton(t("reset_all"), x, y + 112, 300, this::resetDefaults);
        addButton(t("close"), x, y + 140, 300, this::onClose);
    }

    private void buildGeneral() {
        int x = this.width / 2 - 190;
        int y = 46;
        addButton(enabled() ? t("mod_enabled") : t("mod_disabled"), x, y, 380, () -> {
            root.addProperty("enabled", !enabled());
            buildPage();
        });

        addLabel(t("spacing_label"), t("spacing_desc"), x, y + 31);
        spacingBox = addEdit(t("spacing_hint"), x, y + 48, 380, String.valueOf(intValue("spacing", 500)));

        addLabel(t("island_y_label"), t("island_y_desc"), x, y + 76);
        islandYBox = addEdit(t("island_y_hint"), x, y + 93, 380, String.valueOf(intValue("island_y", 100)));

        addLabel(t("radius_label"), t("radius_desc"), x, y + 121);
        radiusBox = addEdit(t("radius_hint"), x, y + 138, 380, String.valueOf(intValue("platform_radius", 0)));

        addButton(t("back"), x, y + 166, 180, () -> { commitGeneralFields(); page = Page.MAIN; buildPage(); });
        saveButton = addButton(t("save"), x + 200, y + 166, 180, this::save);
    }

    private void buildStages() {
        int x = this.width / 2 - 220;
        int y = 45;
        JsonArray stages = stages();
        for (int i = 0; i < Math.min(stages.size(), 9); i++) {
            JsonObject stage = stages.get(i).getAsJsonObject();
            int row = i;
            Component text = Component.translatable("oneblockreborn.stage_list", stage.get("id").getAsInt(), stageName(stage), stage.get("required_blocks").getAsLong())
                    .append(stage.get("enabled").getAsBoolean() ? Component.empty() : Component.translatable("oneblockreborn.disabled_suffix"));
            addButton(text, x, y + i * 25, 440, () -> {
                stageIndex = row;
                page = Page.STAGE;
                buildPage();
            });
        }
        int bottom = y + Math.min(stages.size(), 9) * 25;
        addButton(t("add_stage"), x, bottom + 5, 210, this::addStage);
        addButton(t("back"), x + 230, bottom + 5, 210, () -> { page = Page.MAIN; buildPage(); });
    }

    private void buildStage() {
        JsonObject stage = selectedStage();
        if (stage == null) { page = Page.STAGES; buildPage(); return; }
        int x = this.width / 2 - 220;
        int y = 34;

        stageNameBox = addDescribedEdit(t("stage_name"), t("stage_name_desc"), t("stage_name_hint"),
                x, y, 440, stageName(stage));
        addButton(stage.get("enabled").getAsBoolean() ? t("stage_enabled") : t("stage_disabled"), x, y + 45, 440, () -> {
            commitStageFields();
            stage.addProperty("enabled", !stage.get("enabled").getAsBoolean());
            buildPage();
        });

        requiredBox = addDescribedEdit(t("required_blocks"), t("required_blocks_desc"), t("required_blocks_hint"),
                x, y + 73, 440, String.valueOf(stage.get("required_blocks").getAsLong()));

        stageMobChanceBox = addDescribedEdit(t("mob_chance"), t("mob_chance_desc"), t("mob_chance_hint"),
                x, y + 118, 440, String.valueOf(stageMobChance(stage)));

        addButton(t("stage_mobs"), x, y + 163, 215, () -> {
            commitStageFields();
            page = Page.MOBS;
            buildPage();
        });
        addButton(t("chests_loot"), x + 225, y + 163, 215, () -> {
            commitStageFields();
            page = Page.CHEST;
            buildPage();
        });

        JsonArray blocks = stage.getAsJsonArray("blocks");
        for (int i = 0; i < Math.min(blocks.size(), 4); i++) {
            JsonObject block = blocks.get(i).getAsJsonObject();
            int row = i;
            Component text = Component.translatable("oneblockreborn.block_list", block.get("block").getAsString(), block.get("weight").getAsInt())
                    .append(block.get("enabled").getAsBoolean() ? Component.empty() : Component.translatable("oneblockreborn.disabled_suffix"));
            addButton(text, x, y + 190 + i * 27, 440, () -> {
                commitStageFields();
                blockIndex = row;
                page = Page.BLOCK;
                buildPage();
            });
        }
        int bottom = y + 190 + Math.min(blocks.size(), 4) * 27;
        addButton(t("add_block"), x, bottom, 210, () -> { commitStageFields(); addBlock(); });
        addButton(t("delete_stage"), x + 230, bottom, 210, this::deleteStage);
        addButton(t("back"), x, bottom + 30, 210, () -> { commitStageFields(); page = Page.STAGES; buildPage(); });
        saveButton = addButton(t("save"), x + 230, bottom + 30, 210, this::save);
    }

    private void buildBlock() {
        JsonObject block = selectedBlock();
        if (block == null) { page = Page.STAGE; buildPage(); return; }
        int x = this.width / 2 - 220;
        int y = 36;
        blockIdBox = addDescribedEdit(t("block_id_label"), t("block_id_desc"), t("block_id_hint"),
                x, y, 440, block.get("block").getAsString());
        addButton(block.get("enabled").getAsBoolean() ? t("block_enabled") : t("block_disabled"), x, y + 45, 440, () -> {
            commitBlockFields();
            block.addProperty("enabled", !block.get("enabled").getAsBoolean());
            buildPage();
        });
        blockWeightBox = addDescribedEdit(t("block_percent"), t("block_percent_desc"), t("block_percent_hint"),
                x, y + 73, 440, String.valueOf(block.get("weight").getAsInt()));
        addButton(t("delete_block"), x, y + 118, 210, this::deleteBlock);
        addButton(t("back"), x + 230, y + 118, 210, () -> { commitBlockFields(); page = Page.STAGE; buildPage(); });
        saveButton = addButton(t("save"), x, y + 155, 440, this::save);
    }

    private void buildMobs() {
        JsonObject stage = selectedStage();
        if (stage == null) { page = Page.STAGES; buildPage(); return; }
        JsonArray mobs = mobs(stage);
        int x = this.width / 2 - 220;
        int y = 45;
        for (int i = 0; i < Math.min(mobs.size(), 9); i++) {
            JsonObject mob = mobs.get(i).getAsJsonObject();
            int row = i;
            addButton(Component.translatable("oneblockreborn.mob_list", mob.get("mob").getAsString(), mob.get("weight").getAsInt()),
                    x, y + i * 27, 440, () -> { mobIndex = row; page = Page.MOB; buildPage(); });
        }
        int bottom = y + Math.min(mobs.size(), 9) * 27;
        addButton(t("add_mob"), x, bottom, 210, this::addMob);
        addButton(t("back"), x + 230, bottom, 210, () -> { page = Page.STAGE; buildPage(); });
    }

    private void buildChest() {
        JsonObject stage = selectedStage();
        if (stage == null) { page = Page.STAGES; buildPage(); return; }
        int x = this.width / 2 - 220;
        int y = 38;
        chestChanceBox = addDescribedEdit(t("chest_chance"), t("chest_chance_desc"), t("chest_chance_hint"),
                x, y, 440, String.valueOf(stageChestChance(stage)));
        addButton(t("configure_loot"), x, y + 45, 440, () -> {
            commitChestFields();
            page = Page.LOOT;
            buildPage();
        });
        addButton(t("back"), x, y + 82, 210, () -> { commitChestFields(); page = Page.STAGE; buildPage(); });
        saveButton = addButton(t("save"), x + 230, y + 82, 210, this::save);
    }

    private void buildLoot() {
        JsonObject stage = selectedStage();
        if (stage == null) { page = Page.STAGES; buildPage(); return; }
        JsonArray loot = loot(stage);
        int x = this.width / 2 - 220;
        int y = 45;
        for (int i = 0; i < Math.min(loot.size(), 7); i++) {
            JsonObject entry = loot.get(i).getAsJsonObject();
            int row = i;
            Component text = Component.translatable("oneblockreborn.loot_list", entry.get("item").getAsString(), entry.get("weight").getAsInt(), entry.get("min_count").getAsInt(), entry.get("max_count").getAsInt());
            addButton(text, x, y + i * 27, 440, () -> { lootIndex = row; page = Page.LOOT_ENTRY; buildPage(); });
        }
        int bottom = y + Math.min(loot.size(), 7) * 27;
        addButton(t("add_item"), x, bottom, 210, this::addLoot);
        addButton(t("back"), x + 230, bottom, 210, () -> { page = Page.CHEST; buildPage(); });
    }

    private void buildLootEntry() {
        JsonObject lootEntry = selectedLoot();
        if (lootEntry == null) { page = Page.LOOT; buildPage(); return; }
        int x = this.width / 2 - 220;
        int y = 32;
        lootItemBox = addDescribedEdit(t("item_id_label"), t("item_id_desc"), t("item_id_hint"),
                x, y, 440, lootEntry.get("item").getAsString());
        lootWeightBox = addDescribedEdit(t("item_percent"), t("item_percent_desc"), t("item_percent_hint"),
                x, y + 45, 440, String.valueOf(lootEntry.get("weight").getAsInt()));
        lootMinBox = addDescribedEdit(t("min_count"), t("min_count_desc"), t("min_count_hint"),
                x, y + 90, 440, String.valueOf(lootEntry.get("min_count").getAsInt()));
        lootMaxBox = addDescribedEdit(t("max_count"), t("max_count_desc"), t("max_count_hint"),
                x, y + 135, 440, String.valueOf(lootEntry.get("max_count").getAsInt()));
        addButton(t("delete_item"), x, y + 180, 210, this::deleteLoot);
        addButton(t("back"), x + 230, y + 180, 210, () -> { commitLootFields(); page = Page.LOOT; buildPage(); });
        saveButton = addButton(t("save"), x, y + 217, 440, this::save);
    }

    private void buildMob() {
        JsonObject mob = selectedMob();
        if (mob == null) { page = Page.MOBS; buildPage(); return; }
        int x = this.width / 2 - 220;
        int y = 34;
        mobIdBox = addDescribedEdit(t("mob_id_label"), t("mob_id_desc"), t("mob_id_hint"),
                x, y, 440, mob.get("mob").getAsString());
        mobWeightBox = addDescribedEdit(t("mob_percent"), t("mob_percent_desc"), t("mob_percent_hint"),
                x, y + 45, 440, String.valueOf(mob.get("weight").getAsInt()));
        addButton(t("delete_mob"), x, y + 90, 210, this::deleteMob);
        addButton(t("back"), x + 230, y + 90, 210, () -> { commitMobFields(); page = Page.MOBS; buildPage(); });
        saveButton = addButton(t("save"), x, y + 127, 440, this::save);
    }

    private EditBox addDescribedEdit(Component label, Component description, Component hint,
                                      int x, int y, int width, String value) {
        addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(
                x, y, width, 12, label, this.font));
        addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(
                x, y + 12, width, 12, description, this.font));
        return addEdit(hint, x, y + 25, width, value);
    }

    private static Component t(String key, Object... args) { return Component.translatable("oneblockreborn." + key, args); }

    private Button addButton(String text, int x, int y, int width, Runnable action) {
        return addButton(Component.literal(text), x, y, width, action);
    }

    private Button addButton(Component text, int x, int y, int width, Runnable action) {
        Button button = Button.builder(text, b -> action.run()).bounds(x, y, width, 20).build();
        addRenderableWidget(button);
        return button;
    }

    private void addLabel(String title, String description, int x, int y) {
        addLabel(Component.literal(title), Component.literal(description), x, y);
    }

    private void addLabel(Component title, Component description, int x, int y) {
        addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(
                x, y, 380, 12, title, this.font));
        addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(
                x, y + 12, 380, 12, description, this.font));
    }

    private EditBox addEdit(String hint, int x, int y, int width, String value) {
        return addEdit(Component.literal(hint), x, y, width, value);
    }

    private EditBox addEdit(Component hint, int x, int y, int width, String value) {
        EditBox box = new EditBox(this.font, x, y, width, 20, hint);
        box.setValue(value);
        box.setHint(hint);
        addRenderableWidget(box);
        return box;
    }

    private JsonArray stages() { return root.getAsJsonArray("stages"); }

    private void ensureStageNames() {
        if (root == null || !root.has("stages") || !root.get("stages").isJsonArray()) return;
        for (var element : root.getAsJsonArray("stages")) {
            if (!element.isJsonObject()) continue;
            JsonObject stage = element.getAsJsonObject();
            if (!stage.has("name") || !stage.get("name").isJsonPrimitive() || stage.get("name").getAsString().isBlank()) {
                stage.addProperty("name", stageName(stage));
            }
        }
    }

    private JsonObject selectedStage() {
        JsonArray a = stages();
        return stageIndex >= 0 && stageIndex < a.size() ? a.get(stageIndex).getAsJsonObject() : null;
    }

    private JsonObject selectedBlock() {
        JsonObject s = selectedStage();
        if (s == null) return null;
        JsonArray a = s.getAsJsonArray("blocks");
        return blockIndex >= 0 && blockIndex < a.size() ? a.get(blockIndex).getAsJsonObject() : null;
    }

    private JsonArray mobs(JsonObject stage) {
        if (!stage.has("mobs") || !stage.get("mobs").isJsonArray()) stage.add("mobs", new JsonArray());
        return stage.getAsJsonArray("mobs");
    }

    private JsonArray loot(JsonObject stage) {
        if (!stage.has("loot") || !stage.get("loot").isJsonArray()) stage.add("loot", new JsonArray());
        return stage.getAsJsonArray("loot");
    }

    private JsonObject selectedLoot() {
        JsonObject s = selectedStage();
        if (s == null) return null;
        JsonArray a = loot(s);
        return lootIndex >= 0 && lootIndex < a.size() ? a.get(lootIndex).getAsJsonObject() : null;
    }

    private JsonObject selectedMob() {
        JsonObject s = selectedStage();
        if (s == null) return null;
        JsonArray a = mobs(s);
        return mobIndex >= 0 && mobIndex < a.size() ? a.get(mobIndex).getAsJsonObject() : null;
    }

    private boolean enabled() { return !root.has("enabled") || root.get("enabled").getAsBoolean(); }
    private int intValue(String key, int fallback) { return root.has(key) ? root.get(key).getAsInt() : fallback; }

    private void commitGeneralFields() {
        if (root == null) return;
        setIntFromBox("spacing", spacingBox, 100, 30_000_000);
        setIntFromBox("island_y", islandYBox, -60, 400);
        setIntFromBox("platform_radius", radiusBox, 0, 16);
    }

    private void commitStageFields() {
        JsonObject s = selectedStage();
        if (s == null) return;
        if (stageNameBox != null) {
            String name = stageNameBox.getValue().trim();
            if (!name.isBlank()) s.addProperty("name", name);
        }
        if (requiredBox != null) setLongFromBox(s, "required_blocks", requiredBox, 0, Long.MAX_VALUE);
        if (stageMobChanceBox != null) setIntFromBox(s, "mob_chance", stageMobChanceBox, 0, 100);
    }

    private void commitBlockFields() {
        JsonObject b = selectedBlock();
        if (b == null) return;
        if (blockIdBox != null) b.addProperty("block", blockIdBox.getValue().trim());
        if (blockWeightBox != null) setIntFromBox(b, "weight", blockWeightBox, 0, 100);
    }

    private void commitChestFields() {
        JsonObject s = selectedStage();
        if (s == null) return;
        if (chestChanceBox != null) setIntFromBox(s, "chest_chance", chestChanceBox, 0, 100);
    }

    private void commitLootFields() {
        JsonObject l = selectedLoot();
        if (l == null) return;
        if (lootItemBox != null) l.addProperty("item", lootItemBox.getValue().trim());
        if (lootWeightBox != null) setIntFromBox(l, "weight", lootWeightBox, 0, 100);
        if (lootMinBox != null) setIntFromBox(l, "min_count", lootMinBox, 1, 64);
        if (lootMaxBox != null) setIntFromBox(l, "max_count", lootMaxBox, 1, 64);
    }

    private void commitMobFields() {
        JsonObject m = selectedMob();
        if (m == null) return;
        if (mobIdBox != null) m.addProperty("mob", mobIdBox.getValue().trim());
        if (mobWeightBox != null) setIntFromBox(m, "weight", mobWeightBox, 0, 100);
    }

    private void setIntFromBox(String key, EditBox box, int min, int max) {
        if (box == null || root == null) return;
        try {
            int value = Integer.parseInt(box.getValue().trim());
            root.addProperty(key, Math.max(min, Math.min(max, value)));
        } catch (NumberFormatException ignored) {
            status = Component.translatable("oneblockreborn.status.invalid_number", key);
        }
    }

    private void setIntFromBox(JsonObject object, String key, EditBox box, int min, int max) {
        try {
            int value = Integer.parseInt(box.getValue().trim());
            object.addProperty(key, Math.max(min, Math.min(max, value)));
        } catch (NumberFormatException ignored) {
            status = Component.translatable("oneblockreborn.status.invalid_integer", key);
        }
    }

    private void setLongFromBox(JsonObject object, String key, EditBox box, long min, long max) {
        try {
            long value = Long.parseLong(box.getValue().trim());
            object.addProperty(key, Math.max(min, Math.min(max, value)));
        } catch (NumberFormatException ignored) {
            status = Component.translatable("oneblockreborn.status.invalid_number", key);
        }
    }

    private String stageName(JsonObject stage) {
        if (stage.has("name") && stage.get("name").isJsonPrimitive() && !stage.get("name").getAsString().isBlank()) {
            return stage.get("name").getAsString();
        }
        int id = stage.has("id") ? stage.get("id").getAsInt() : 1;
        return switch (id) {
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
            default -> "Stage " + id;
        };
    }

    private int blockPercent(JsonObject block) {
        return block.has("weight") ? Math.max(0, Math.min(100, block.get("weight").getAsInt())) : 0;
    }

    private int stageMobChance(JsonObject stage) {
        return stage.has("mob_chance") ? stage.get("mob_chance").getAsInt() : 0;
    }

    private int stageChestChance(JsonObject stage) {
        return stage.has("chest_chance") ? stage.get("chest_chance").getAsInt() : 0;
    }

    private void addStage() {
        commitGeneralFields();
        int maxId = 0;
        for (var e : stages()) maxId = Math.max(maxId, e.getAsJsonObject().get("id").getAsInt());
        JsonObject s = new JsonObject();
        s.addProperty("id", maxId + 1);
        s.addProperty("name", "Stage " + (maxId + 1));
        s.addProperty("enabled", true);
        s.addProperty("required_blocks", 0);
        s.addProperty("mob_chance", 0);
        s.add("mobs", new JsonArray());
        JsonArray blocks = new JsonArray();
        blocks.add(newBlock());
        s.add("blocks", blocks);
        stages().add(s);
        stageIndex = stages().size() - 1;
        page = Page.STAGE;
        buildPage();
    }

    private JsonObject newBlock() {
        JsonObject b = new JsonObject();
        b.addProperty("block", "minecraft:stone");
        b.addProperty("enabled", true);
        b.addProperty("weight", 0);
        return b;
    }

    private void addBlock() {
        JsonObject s = selectedStage();
        if (s == null) return;
        s.getAsJsonArray("blocks").add(newBlock());
        blockIndex = s.getAsJsonArray("blocks").size() - 1;
        page = Page.BLOCK;
        buildPage();
    }

    private void addMob() {
        JsonObject s = selectedStage();
        if (s == null) return;
        JsonObject m = new JsonObject();
        m.addProperty("mob", "minecraft:zombie");
        m.addProperty("weight", 0);
        mobs(s).add(m);
        mobIndex = mobs(s).size() - 1;
        page = Page.MOB;
        buildPage();
    }

    private void addLoot() {
        JsonObject s = selectedStage();
        if (s == null) return;
        JsonObject l = new JsonObject();
        l.addProperty("item", "minecraft:bread");
        l.addProperty("weight", 0);
        l.addProperty("min_count", 1);
        l.addProperty("max_count", 3);
        loot(s).add(l);
        lootIndex = loot(s).size() - 1;
        page = Page.LOOT_ENTRY;
        buildPage();
    }

    private void deleteLoot() {
        JsonObject s = selectedStage();
        if (s == null) return;
        loot(s).remove(lootIndex);
        lootIndex = Math.max(0, lootIndex - 1);
        page = Page.LOOT;
        buildPage();
    }

    private void deleteStage() {
        if (stages().size() <= 1) { status = Component.translatable("oneblockreborn.status.cannot_delete_last_stage"); return; }
        stages().remove(stageIndex);
        stageIndex = Math.max(0, stageIndex - 1);
        page = Page.STAGES;
        buildPage();
    }

    private void deleteBlock() {
        JsonObject s = selectedStage();
        if (s == null) return;
        JsonArray a = s.getAsJsonArray("blocks");
        if (a.size() <= 1) { status = Component.translatable("oneblockreborn.status.last_block"); return; }
        a.remove(blockIndex);
        blockIndex = Math.max(0, blockIndex - 1);
        page = Page.STAGE;
        buildPage();
    }

    private void deleteMob() {
        JsonObject s = selectedStage();
        if (s == null) return;
        mobs(s).remove(mobIndex);
        mobIndex = Math.max(0, mobIndex - 1);
        page = Page.MOBS;
        buildPage();
    }

    private void request() {
        status = Component.translatable("oneblockreborn.status.requesting");
        if (Minecraft.getInstance().getConnection() == null) {
            status = Component.translatable("oneblockreborn.status.no_connection");
            return;
        }
        PacketDistributor.sendToServer(new OneBlockNetwork.RequestConfig());
    }

    private void save() {
        commitGeneralFields();
        commitStageFields();
        commitBlockFields();
        commitMobFields();
        commitChestFields();
        commitLootFields();
        ensureStageNames();
        if (saveInFlight || root == null) return;
        String json = GSON.toJson(root);
        if (json.length() > 1_000_000) { status = Component.translatable("oneblockreborn.status.too_large"); return; }
        returnPageAfterSave = page;
        saveInFlight = true;
        if (saveButton != null) saveButton.active = false;
        status = Component.translatable("oneblockreborn.status.saving");
        PacketDistributor.sendToServer(new OneBlockNetwork.SaveConfig(json));
    }

    private void resetDefaults() {
        if (saveInFlight) return;
        saveInFlight = true;
        if (saveButton != null) saveButton.active = false;
        status = Component.translatable("oneblockreborn.status.resetting");
        PacketDistributor.sendToServer(new OneBlockNetwork.ResetConfig());
    }

    public void setServerConfig(String json) {
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
            ensureStageNames();
            status = Component.translatable("oneblockreborn.status.loaded");
            Page targetPage = returnPageAfterSave != null ? returnPageAfterSave : Page.MAIN;
            returnPageAfterSave = null;
            page = targetPage;
            saveInFlight = false;
            buildPage();
        } catch (Exception e) {
            status = Component.translatable("oneblockreborn.status.read_error", e.getMessage());
        }
    }

    public void showResult(boolean success, String message) {
        status = Component.literal(message);
        saveInFlight = false;
        if (saveButton != null) saveButton.active = true;
        if (success) request();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        Component breadcrumb = switch (page) {
            case MAIN -> t("breadcrumb_main");
            case GENERAL -> t("breadcrumb_general");
            case STAGES -> t("breadcrumb_stages");
            case STAGE -> t("breadcrumb_stage");
            case BLOCK -> t("breadcrumb_block");
            case MOBS -> t("breadcrumb_mobs");
            case MOB -> t("breadcrumb_mob");
            case CHEST -> t("breadcrumb_chest");
            case LOOT -> t("breadcrumb_loot");
            case LOOT_ENTRY -> t("breadcrumb_loot_entry");
        };
        graphics.drawCenteredString(font, breadcrumb, width / 2, 28, 0xAAAAAA);
        graphics.drawCenteredString(font, status, width / 2, height - 18, 0xAAAAAA);
    }
}
