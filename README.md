# One-Block Reborn

One-Block Reborn is a NeoForge 1.21.1 Minecraft mod built around the classic One Block idea, with independent player islands, cooperative help, editable stages, mob spawning and an in-game configuration editor.

## Requirements

- Minecraft Java Edition 1.21.1
- NeoForge 21.1.213 or compatible 21.1.x build
- Java 21 for development/building

## Core gameplay

Each player gets their own independent One Block island in the void.

Breaking the central block:

1. drops the resources from the old block;
2. temporarily removes the block;
3. can spawn a mob according to the current stage;
4. increments the owner's One Block progress;
5. changes the stage when its required amount is reached;
6. immediately creates the next random block from the stage table.

The progress belongs to the **owner's One Block**, not to the player who happens to break it.

### Cooperative mining

A friend can travel to another player's island and help break that player's One Block.

For example:

> _sn0w1_ has 1,999 broken blocks. A friend breaks the next block on _sn0w1_'s island, so _sn0w1_'s progress becomes 2,000.

The friend's own One Block progress does not change.

Other players cannot accidentally use a different island's progress: the broken center position is resolved to its owner before the One Block cycle is performed.

## Stage notifications

When a One Block reaches a new stage, the owner receives a dedicated notification containing:

- the new stage number;
- the configured stage name;
- the player who broke the last required block that caused the stage transition.

Players currently within 7 blocks of the One Block also receive a cooperative-progress notification.

Example for the owner:

> **Ваш One Block был обновлён! Этап: 4 / Ocean. Последний необходимый блок сломал: _sn0w1_.**

All stage-transition notifications use the player's Minecraft language (Russian or English).

### Explosion recovery

If a Creeper or another non-player event destroys the central One Block and leaves air at its position, the mod detects the missing block on the next server tick and places a new One Block there. Existing non-air blocks such as the normal next block or a generated chest are not overwritten.

Custom stage names are used in the message automatically.

## Default stages

The built-in configuration contains ten themed stages:

1. The Plains
2. The Underground
3. Icy Tundra
4. Ocean
5. Jungle Dungeon
6. Red Desert
7. The Nether
8. Idyll
9. Desolate Land
10. The End

Every stage can be renamed or replaced through the configuration screen.

## In-game configuration

OP level 2 or higher can open the One-Block Reborn configuration screen from the NeoForge Mods screen.

The editor allows you to change:

- whether the mod is enabled;
- island spacing;
- island Y coordinate;
- optional platform radius;
- stage name;
- stage enabled state;
- required number of broken blocks;
- stage mob chance from **0 to 100% using a text field**;
- block IDs;
- block enabled state;
- block weights using a text field;
- stage mob IDs;
- mob weights using a text field;
- add/delete stages;
- add/delete blocks;
- add/delete mobs.

### Weights

Weights are relative values, not fixed percentages.

For example:

```text
Stone  = 50
Dirt   = 30
Oak    = 20
```

means approximately 50% / 30% / 20% of selections.

The GUI displays the approximate percentage next to each block.

### Reset settings

The main configuration screen contains **Reset ALL settings**.

It restores the complete built-in configuration, including all default stages, blocks, mob lists and general settings.

**It does not reset player One Block progress or island positions.**

The same operation is available to OPs with:

```text
/oneblockreborn resetconfig
```

## Configuration file

The world-specific configuration is stored in:

```text
<world>/data/oneblockreborn.json
```

This means singleplayer worlds keep their own configuration after closing and reopening the world.

## Commands

All commands require OP level 2+.

```text
/oneblockreborn reload
/oneblockreborn resetconfig
/oneblockreborn info <player>
/oneblockreborn stage <player> <stage>
/oneblockreborn reset <player>
```

`resetconfig` resets only the configuration.

`reset <player>` resets the selected player's One Block progress and creates a new island for that player.

## Mod icon

The mod icon is:

```text
src/main/resources/logo.png
```

It is referenced from `META-INF/neoforge.mods.toml` with `logoFile="logo.png"`.

You can replace `logo.png` with your own 256x256 or 512x512 PNG without changing the Java code.

## Windows build scripts

The project contains:

```text
build.bat
build_clean.bat
run_client.bat
run_server.bat
```

`build.bat` runs a normal Gradle build.

`build_clean.bat` runs `clean build`.

The scripts use `gradlew.bat` if a Gradle Wrapper is present; otherwise they use a `gradle` installation available in PATH.

## Package naming

All Java source files use:

```text
ru.sn0w1.oneblockreborn
```

The creator name is intentionally not part of the displayed mod name. The project uses `sn0w1` without underscores for Java package paths.

## License

One-Block Reborn Custom License. See `LICENSE`.


### v0.7.7

Исправлена критическая проблема, из-за которой один неверный ID блока в сохранённой конфигурации мог откатывать активную конфигурацию к этапу только с `minecraft:stone`. Добавлена миграция старых ID `minecraft:grass`, `minecraft:quartz_ore` и `minecraft:glow_berries`.

## Безопасные блоки One Block

Стандартная конфигурация не использует растения, листву, культуры, ковры и другие тонкие блоки, через которые игрок может провалиться или на которых нельзя надёжно стоять. В частности исключены трава, пшеница, морковь, картофель, свёкла, саженцы, листья, лозы, водоросли, моховой ковёр и снеговые слои.

Такие блоки также фильтруются при выборе следующего блока, поэтому старый или вручную добавленный небезопасный ID не сможет случайно появиться как следующий One Block.

Базовый шанс появления мобов в стандартных этапах снижен до 3–10% в зависимости от этапа.


## Сундуки с кастомным лутом

Для каждого этапа можно настроить шанс появления сундука и собственную таблицу лута. В GUI: `Этап → Сундуки и лут`.

Каждый предмет лута имеет:
- ID предмета, например `minecraft:diamond`;
- относительный вес;
- минимальное количество;
- максимальное количество.

При срабатывании шанса сундук появляется в центре One Block вместо следующего блока и заполняется случайными предметами из таблицы. Если шанс равен `0%`, сундуки этого этапа не появляются.

Пример:

```text
Шанс сундука: 5%

minecraft:iron_ingot   вес 5   1–4
minecraft:gold_ingot   вес 3   1–3
minecraft:diamond      вес 1   1–2
minecraft:bread        вес 6   2–6
```

Вес является относительным: чем выше вес, тем чаще предмет выбирается среди остальных предметов таблицы.
