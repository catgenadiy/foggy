package dev.groundfog.client;

import dev.groundfog.FogConfig;
import dev.groundfog.GroundFogMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Целевая сила тумана (время суток, погода, /weather fog) + плавное сглаживание,
 * чтобы туман наползал и рассеивался постепенно, а не выключался щелчком.
 */
@EventBusSubscriber(modid = GroundFogMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class FogEnv {

    private static float smoothed = 0.0f;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            smoothed = 0.0f;
            return;
        }
        float target = targetStrength(mc.level);
        smoothed += (target - smoothed) * 0.02f; // ~2-4 сек на переход

        if (dev.groundfog.FogConfig.SPEC.isLoaded()) {
            VolumetricFogRenderer.tickMaps(mc); // обновление каскадов карт
        }
    }

    public static float smoothedStrength() {
        return smoothed;
    }

    private static float targetStrength(ClientLevel level) {
        long gameTime = level.getGameTime();
        if (ClientFogState.isForced(gameTime)) return 1.0f;
        if (ClientFogState.isSuppressed(gameTime)) return 0.0f;

        long t = level.getDayTime() % 24000L;
        float night = FogConfig.NIGHT_FOG.get() ? 0.35f : 0.0f;

        float time;
        if (t < 1500L) {
            time = 1.0f;                                            // рассвет — пик
        } else if (t < 3000L) {
            time = 1.0f - (t - 1500L) / 1500.0f;                    // солнце поднялось — тает
        } else if (t < 12500L) {
            time = 0.0f;                                            // день
        } else if (t < 22000L) {
            time = night;                                           // ночь — дымка
        } else {
            time = night + (1.0f - night) * (t - 22000L) / 2000.0f; // сгущается к рассвету
        }

        float weather = 1.0f;
        if (level.isThundering()) {
            weather = 0.4f;
        } else if (level.isRaining()) {
            weather = 0.8f;
        }

        return time * weather;
    }

    private FogEnv() {}
}
