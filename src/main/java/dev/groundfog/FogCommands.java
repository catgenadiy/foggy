package dev.groundfog;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.groundfog.net.ForcedFogPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * /weather fog [длительность] — включить наземный туман (по умолчанию 5 минут).
 * /weather clear|rain|thunder — гасит принудительный туман;
 * clear дополнительно подавляет естественный туман на 5 минут.
 */
@EventBusSubscriber(modid = GroundFogMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FogCommands {

    private static final int DEFAULT_DURATION_TICKS = 6000; // 5 минут
    private static final int CLEAR_SUPPRESS_TICKS = 6000;

    /** До какого серверного тика действует принудительный туман (для синка входящим игрокам). */
    private static long forcedUntilTick = 0L;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // brigadier сольёт наш литерал "weather" с ванильным деревом /weather
        event.getDispatcher().register(
                Commands.literal("weather")
                        .then(Commands.literal("fog")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> setFog(ctx.getSource(), DEFAULT_DURATION_TICKS))
                                .then(Commands.argument("duration", TimeArgument.time(1))
                                        .executes(ctx -> setFog(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "duration"))))));
    }

    private static int setFog(CommandSourceStack source, int durationTicks) {
        MinecraftServer server = source.getServer();
        forcedUntilTick = source.getLevel().getGameTime() + durationTicks;
        broadcast(server, new ForcedFogPayload(true, durationTicks));
        source.sendSuccess(() -> Component.literal(
                "Наземный туман включён на " + (durationTicks / 20) + " с"), true);
        return 1;
    }

    /** Перехват ванильных /weather clear|rain|thunder — гасим туман. */
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        String input = event.getParseResults().getReader().getString().trim();
        if (input.startsWith("/")) input = input.substring(1);
        if (!input.startsWith("weather ")) return;

        boolean clear = input.startsWith("weather clear");
        if (clear || input.startsWith("weather rain") || input.startsWith("weather thunder")) {
            forcedUntilTick = 0L;
            MinecraftServer server = event.getParseResults().getContext()
                    .getSource().getServer();
            broadcast(server, new ForcedFogPayload(false, clear ? CLEAR_SUPPRESS_TICKS : 0));
        }
    }

    /** Игрок зашёл во время принудительного тумана — досылаем состояние. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        long remaining = forcedUntilTick - player.serverLevel().getGameTime();
        if (remaining > 0 && player.connection.hasChannel(ForcedFogPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new ForcedFogPayload(true, (int) remaining));
        }
    }

    private static void broadcast(MinecraftServer server, ForcedFogPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection.hasChannel(ForcedFogPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private FogCommands() {}
}
