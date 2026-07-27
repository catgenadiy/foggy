package dev.groundfog.client;

import dev.groundfog.net.ForcedFogPayload;
import net.minecraft.client.Minecraft;

/**
 * Клиентское состояние принудительного тумана (/weather fog | clear).
 */
public final class ClientFogState {
    private static long forcedUntil = 0L;
    private static long suppressedUntil = 0L;

    public static void handle(ForcedFogPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();

        if (payload.forced()) {
            forcedUntil = now + payload.durationTicks();
            suppressedUntil = 0L;
        } else {
            forcedUntil = 0L;
            suppressedUntil = now + payload.durationTicks();
        }
    }

    public static boolean isForced(long gameTime) {
        return gameTime < forcedUntil;
    }

    public static boolean isSuppressed(long gameTime) {
        return gameTime < suppressedUntil;
    }

    /** Сброс при смене мира (gameTime другого мира не совместим). */
    public static void reset() {
        forcedUntil = 0L;
        suppressedUntil = 0L;
    }

    private ClientFogState() {}
}
