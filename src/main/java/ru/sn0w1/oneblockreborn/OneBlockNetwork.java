package ru.sn0w1.oneblockreborn;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class OneBlockNetwork {
    private static final String PROTOCOL = "1";
    private static final int MAX_CONFIG_SIZE = 1_000_000;

    public static final CustomPacketPayload.Type<RequestConfig> REQUEST_CONFIG_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "request_config"));
    public static final CustomPacketPayload.Type<ConfigData> CONFIG_DATA_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "config_data"));
    public static final CustomPacketPayload.Type<SaveConfig> SAVE_CONFIG_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "save_config"));
    public static final CustomPacketPayload.Type<ConfigResult> CONFIG_RESULT_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "config_result"));
    public static final CustomPacketPayload.Type<ResetConfig> RESET_CONFIG_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "reset_config"));

    private OneBlockNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);

        registrar.playToServer(REQUEST_CONFIG_TYPE, RequestConfig.STREAM_CODEC, OneBlockNetwork::handleRequest);
        registrar.playToServer(SAVE_CONFIG_TYPE, SaveConfig.STREAM_CODEC, OneBlockNetwork::handleSave);
        registrar.playToServer(RESET_CONFIG_TYPE, ResetConfig.STREAM_CODEC, OneBlockNetwork::handleReset);

        // NeoForge 21.1.213 requires the clientbound handler directly on playToClient.
        // The handler itself delegates to the physical-client-only hook without linking
        // Minecraft client classes into the common network class.
        registrar.playToClient(CONFIG_DATA_TYPE, ConfigData.STREAM_CODEC, OneBlockNetwork::handleConfigData);
        registrar.playToClient(CONFIG_RESULT_TYPE, ConfigResult.STREAM_CODEC, OneBlockNetwork::handleConfigResult);
    }

    private static void handleRequest(RequestConfig payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        context.enqueueWork(() -> {
            if (!player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player,
                        new ConfigResult(false, "Недостаточно прав: нужен OP уровня 2+."));
                return;
            }

            String json = OneBlockConfig.readRawJson(player.server);
            PacketDistributor.sendToPlayer(player, new ConfigData(json));
        });
    }

    private static void handleSave(SaveConfig payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        context.enqueueWork(() -> {
            if (!player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player,
                        new ConfigResult(false, "Недостаточно прав: нужен OP уровня 2+."));
                return;
            }

            if (payload.json().length() > MAX_CONFIG_SIZE) {
                PacketDistributor.sendToPlayer(player,
                        new ConfigResult(false, "Конфигурация слишком большая."));
                return;
            }

            OneBlockConfig.ApplyResult result = OneBlockConfig.writeAndReload(player.server, payload.json());
            PacketDistributor.sendToPlayer(player, new ConfigResult(result.success(), result.message()));
        });
    }

    private static void handleReset(ResetConfig payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        context.enqueueWork(() -> {
            if (!player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player,
                        new ConfigResult(false, "Недостаточно прав: нужен OP уровня 2+."));
                return;
            }

            OneBlockConfig.ApplyResult result = OneBlockConfig.resetToDefaults(player.server);
            PacketDistributor.sendToPlayer(player, new ConfigResult(result.success(), result.message()));
        });
    }

    private static void handleConfigData(ConfigData payload, IPayloadContext context) {
        context.enqueueWork(() -> invokeClientHook("receiveConfig", new Class<?>[]{String.class}, payload.json()));
    }

    private static void handleConfigResult(ConfigResult payload, IPayloadContext context) {
        context.enqueueWork(() -> invokeClientHook("receiveResult",
                new Class<?>[]{boolean.class, String.class}, payload.success(), payload.message()));
    }

    private static void invokeClientHook(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> hooks = Class.forName("ru.sn0w1.oneblockreborn.client.OneBlockClientHooks");
            hooks.getMethod(methodName, parameterTypes).invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke One-Block Reborn client network hook: " + methodName, e);
        }
    }

    public record RequestConfig() implements CustomPacketPayload {
        public static final StreamCodec<ByteBuf, RequestConfig> STREAM_CODEC =
                StreamCodec.unit(new RequestConfig());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return REQUEST_CONFIG_TYPE;
        }
    }

    public record ConfigData(String json) implements CustomPacketPayload {
        public static final StreamCodec<ByteBuf, ConfigData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ConfigData::json,
                ConfigData::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONFIG_DATA_TYPE;
        }
    }

    public record SaveConfig(String json) implements CustomPacketPayload {
        public static final StreamCodec<ByteBuf, SaveConfig> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveConfig::json,
                SaveConfig::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SAVE_CONFIG_TYPE;
        }
    }

    public record ResetConfig() implements CustomPacketPayload {
        public static final StreamCodec<ByteBuf, ResetConfig> STREAM_CODEC =
                StreamCodec.unit(new ResetConfig());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return RESET_CONFIG_TYPE;
        }
    }

    public record ConfigResult(boolean success, String message) implements CustomPacketPayload {
        public static final StreamCodec<ByteBuf, ConfigResult> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ConfigResult::success,
                ByteBufCodecs.STRING_UTF8, ConfigResult::message,
                ConfigResult::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONFIG_RESULT_TYPE;
        }
    }
}
