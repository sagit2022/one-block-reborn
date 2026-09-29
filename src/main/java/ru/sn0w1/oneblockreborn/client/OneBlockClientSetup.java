package ru.sn0w1.oneblockreborn.client;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class OneBlockClientSetup {
    private OneBlockClientSetup() {}

    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, new IConfigScreenFactory() {
            @Override
            public Screen createScreen(ModContainer modContainer, Screen parent) {
                return new OneBlockConfigScreen(parent);
            }
        });
    }
}
