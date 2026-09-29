package ru.sn0w1.oneblockreborn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class OneBlockClientHooks {
    private OneBlockClientHooks() {}

    public static void receiveConfig(String json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ru.sn0w1.oneblockreborn.client.OneBlockConfigScreen screen) {
            screen.setServerConfig(json);
        }
    }

    public static void receiveResult(boolean success, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ru.sn0w1.oneblockreborn.client.OneBlockConfigScreen screen) {
            screen.showResult(success, message);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal((success ? "§a" : "§c") + message), false);
        }
    }
}
