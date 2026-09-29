package ru.sn0w1.oneblockreborn;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import ru.sn0w1.oneblockreborn.client.OneBlockClientSetup;

@Mod(OneBlockMod.MOD_ID)
public final class OneBlockMod {
    public static final String MOD_ID = "oneblockreborn";

    public OneBlockMod(IEventBus modBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(OneBlockEvents.class);
        NeoForge.EVENT_BUS.addListener(OneBlockCommands::register);
        modBus.addListener(OneBlockNetwork::register);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            OneBlockClientSetup.registerConfigScreen(modContainer);
        }
    }
}
