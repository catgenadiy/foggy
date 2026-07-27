package dev.groundfog;

import dev.groundfog.net.ForcedFogPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(GroundFogMod.MODID)
public class GroundFogMod {
    public static final String MODID = "groundfog";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public GroundFogMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, FogConfig.SPEC);
        modBus.addListener(GroundFogMod::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        // обработчик выполняется только на клиенте (playToClient), на главном потоке
        registrar.playToClient(ForcedFogPayload.TYPE, ForcedFogPayload.STREAM_CODEC,
                (payload, context) -> dev.groundfog.client.ClientFogState.handle(payload));
    }
}
