package dev.groundfog.net;

import dev.groundfog.GroundFogMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сервер -> клиент: принудительный туман.
 * forced=true  — включить туман на durationTicks;
 * forced=false — погасить туман (durationTicks — на сколько тиков подавить
 *                и естественный туман, 0 = не подавлять).
 */
public record ForcedFogPayload(boolean forced, int durationTicks) implements CustomPacketPayload {

    public static final Type<ForcedFogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(GroundFogMod.MODID, "forced_fog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForcedFogPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ForcedFogPayload::forced,
                    ByteBufCodecs.VAR_INT, ForcedFogPayload::durationTicks,
                    ForcedFogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
