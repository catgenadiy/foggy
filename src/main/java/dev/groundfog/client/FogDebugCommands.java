package dev.groundfog.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.groundfog.FogConfig;
import dev.groundfog.GroundFogMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Клиентские команды отладки: /gfog ...
 *
 * Выполняются локально (RegisterClientCommandsEvent), права оператора не нужны,
 * на сервер ничего не уходит — можно отлаживать на чужом сервере.
 *
 *   /gfog status              — состояние каскадов, юниформов, силы тумана
 *   /gfog probe               — что карта думает про точку под игроком
 *   /gfog view <0..8>         — отладочный экран (0 = выкл)
 *   /gfog set <параметр> <v>  — density|thickness|maxdistance|steps без перезахода
 *   /gfog noise <0|1>         — выключить/включить клубление
 *   /gfog freeze <0|1>        — заморозить обновление каскадов
 *   /gfog rescan              — форсировать пересканирование карт
 *   /gfog dump                — сохранить PNG каскадов в screenshots/
 *   /gfog reset               — сбросить все рантайм-оверрайды
 */
@EventBusSubscriber(modid = GroundFogMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class FogDebugCommands {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gfog")
                .executes(ctx -> status(ctx.getSource()));

        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(Commands.literal("probe").executes(ctx -> probe(ctx.getSource())));
        root.then(Commands.literal("rescan").executes(ctx -> rescan(ctx.getSource())));
        root.then(Commands.literal("dump").executes(ctx -> dump(ctx.getSource())));
        root.then(Commands.literal("reset").executes(ctx -> {
            FogDebug.reset();
            say(ctx.getSource(), "Оверрайды сброшены, всё берётся из конфига");
            return 1;
        }));

        root.then(Commands.literal("view")
                .then(Commands.argument("n", IntegerArgumentType.integer(0, 8))
                        .executes(ctx -> {
                            int v = IntegerArgumentType.getInteger(ctx, "n");
                            FogDebug.view = v == 0 ? -1 : v;
                            say(ctx.getSource(), "Отладочный вид: " + v + " — " + viewName(v));
                            return 1;
                        })));

        root.then(Commands.literal("noise")
                .then(Commands.argument("on", IntegerArgumentType.integer(0, 1))
                        .executes(ctx -> {
                            FogDebug.noNoise = IntegerArgumentType.getInteger(ctx, "on") == 0;
                            say(ctx.getSource(), "Клубление: " + (FogDebug.noNoise ? "выкл" : "вкл"));
                            return 1;
                        })));

        root.then(Commands.literal("freeze")
                .then(Commands.argument("on", IntegerArgumentType.integer(0, 1))
                        .executes(ctx -> {
                            FogDebug.freezeMaps = IntegerArgumentType.getInteger(ctx, "on") == 1;
                            say(ctx.getSource(), "Заморозка каскадов: " + FogDebug.freezeMaps);
                            return 1;
                        })));

        root.then(Commands.literal("set")
                .then(Commands.argument("param", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, sb) -> {
                            for (String s : new String[] {"density", "thickness", "maxdistance",
                                    "steps", "sunglow", "dither"}) {
                                sb.suggest(s);
                            }
                            return sb.buildFuture();
                        })
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0f, 512f))
                                .executes(ctx -> set(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "param"),
                                        FloatArgumentType.getFloat(ctx, "value"))))));

        event.getDispatcher().register(root);
    }

    // ---------- команды ----------

    private static int set(CommandSourceStack src, String param, float v) {
        switch (param.toLowerCase(Locale.ROOT)) {
            case "density" -> FogDebug.density = v;
            case "thickness" -> FogDebug.thickness = v;
            case "maxdistance" -> FogDebug.maxDistance = v;
            case "steps" -> FogDebug.steps = v;
            case "sunglow" -> FogDebug.sunGlow = v;
            case "dither" -> FogDebug.dither = v;
            default -> {
                say(src, "§cНеизвестный параметр: " + param);
                return 0;
            }
        }
        say(src, param + " = " + v + " (рантайм, конфиг не тронут)");
        if (param.equalsIgnoreCase("density") && v > 1.5f) {
            say(src, "   §eдиапазон density в конфиге — 0..2, рабочие значения 0.1..0.5.");
            say(src, "   §eПри таких величинах туман насыщается на первом же сегменте: "
                    + "видно зерно интегрирования и клубление превращается в кляксы.");
            say(src, "   §eЧтобы сделать туман гуще, поднимай §fthickness§e, а не density.");
        }
        return 1;
    }

    private static int rescan(CommandSourceStack src) {
        FogMap n = VolumetricFogRenderer.nearMap();
        FogMap f = VolumetricFogRenderer.farMap();
        if (n != null) n.forceRescan();
        if (f != null) f.forceRescan();
        say(src, "Пересканирование каскадов запущено");
        return 1;
    }

    private static int dump(CommandSourceStack src) {
        FogMap n = VolumetricFogRenderer.nearMap();
        FogMap f = VolumetricFogRenderer.farMap();
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            if (n != null && n.isReady()) n.dumpPng(dir.resolve("groundfog-near.png"));
            if (f != null && f.isReady()) f.dumpPng(dir.resolve("groundfog-far.png"));
            say(src, "Карты сохранены в screenshots/groundfog-near.png и -far.png "
                    + "(R=верх слоя, G=множитель, B=толщина/32, чёрное=дыра)");
            return 1;
        } catch (Exception e) {
            say(src, "§cОшибка дампа: " + e);
            GroundFogMod.LOGGER.error("[groundfog] dump failed", e);
            return 0;
        }
    }

    private static int status(CommandSourceStack src) {
        FogMap n = VolumetricFogRenderer.nearMap();
        FogMap f = VolumetricFogRenderer.farMap();
        say(src, "§7=== groundfog ===");
        say(src, String.format(Locale.ROOT,
                "сила=%.3f  кадров отрисовано=%d  режим=%s  вид=%d",
                VolumetricFogRenderer.dbgStrength, VolumetricFogRenderer.dbgFrames,
                FogConfig.RENDER_MODE.get(),
                FogDebug.view >= 0 ? FogDebug.view : FogConfig.DEBUG_VIEW.get()));
        say(src, String.format(Locale.ROOT,
                "density=%.3f thickness=%.1f дальность=%.0f бл. steps=%.0f%s",
                FogDebug.or(FogDebug.density, FogConfig.DENSITY.get()),
                FogDebug.or(FogDebug.thickness, FogConfig.THICKNESS.get()),
                VolumetricFogRenderer.fogFarDistance(Minecraft.getInstance()),
                FogDebug.or(FogDebug.steps, FogConfig.STEPS.get()),
                FogDebug.density != null || FogDebug.thickness != null
                        || FogDebug.maxDistance != null || FogDebug.steps != null
                        ? " §e(есть оверрайды)" : ""));
        say(src, String.format(Locale.ROOT,
                "плита slab=[%.1f .. %.1f]  запасной слой: h=%.1f ok=%.2f",
                VolumetricFogRenderer.dbgSlabMin, VolumetricFogRenderer.dbgSlabMax,
                VolumetricFogRenderer.dbgFarAvgH, VolumetricFogRenderer.dbgFarAvgOk));
        say(src, String.format(Locale.ROOT,
                "цвет: fog=(%.3f %.3f %.3f) ночной минимум=(%.3f %.3f %.3f)",
                VolumetricFogRenderer.dbgFogColor[0], VolumetricFogRenderer.dbgFogColor[1],
                VolumetricFogRenderer.dbgFogColor[2],
                VolumetricFogRenderer.dbgAmbient[0], VolumetricFogRenderer.dbgAmbient[1],
                VolumetricFogRenderer.dbgAmbient[2]));
        say(src, String.format(Locale.ROOT,
                "закат: гашение ванильной подкраски=%.2f  свечение к солнцу=%.2f  дизеринг=%.2f",
                VolumetricFogRenderer.dbgSunDesat,
                FogDebug.or(FogDebug.sunGlow, FogConfig.SUN_GLOW.get()),
                FogDebug.or(FogDebug.dither, FogConfig.DITHER.get())));
        say(src, "ближний: " + mapInfo(n));
        say(src, "дальний: " + mapInfo(f));
        return 1;
    }

    private static String mapInfo(FogMap m) {
        if (m == null) return "§cнет";
        return String.format(Locale.ROOT,
                "%dx%d тексель=%d блок(ов) охват=%d бл. origin=(%d,%d) готов=%s скан=%s "
                        + "обработка=%.1fмс minRaw=%.1f maxTop=%.1f avgH=%.1f avgOk=%.2f",
                m.sizeTex, m.sizeTex, m.texelSize, m.spanBlocks(), m.originX(), m.originZ(),
                m.isReady(), m.isScanning(), m.lastProcessNanos / 1.0e6,
                m.statMinRaw, m.statMaxTop, m.statAvgH, m.statAvgOk);
    }

    private static int probe(CommandSourceStack src) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return 0;
        Vec3 p = mc.player.position();
        float eyeY = (float) mc.player.getEyeY();

        FogMap n = VolumetricFogRenderer.nearMap();
        FogMap f = VolumetricFogRenderer.farMap();
        say(src, String.format(Locale.ROOT, "§7=== проба (%.1f, %.1f, %.1f) ===", p.x, p.y, p.z));
        probeOne(src, "ближний", n, p, eyeY);
        probeOne(src, "дальний", f, p, eyeY);
        return 1;
    }

    private static void probeOne(CommandSourceStack src, String name, FogMap m, Vec3 p, float eyeY) {
        if (m == null || !m.isReady()) {
            say(src, name + ": §cне готов");
            return;
        }
        FogMap.Probe pr = m.probe(p.x, p.z);
        if (!pr.inMap) {
            say(src, name + ": §cточка вне карты (origin=" + m.originX() + "," + m.originZ()
                    + " охват=" + m.spanBlocks() + ")");
            return;
        }
        float thick = FogDebug.or(FogDebug.thickness, FogConfig.THICKNESS.get());
        float density = FogDebug.or(FogDebug.density, FogConfig.DENSITY.get());
        float layerTop = pr.top + thick; // шейдер добавляет толщину к верху карты
        say(src, String.format(Locale.ROOT,
                "%s: тексель(%d,%d) рельеф=%.1f верх_карты=%.1f верх_слоя=%.1f "
                        + "множитель=%.2f дно_округи=%.1f свет=%.0f природа=%s%s",
                name, pr.texelX, pr.texelZ, pr.raw, pr.top, layerTop, pr.fac, pr.valley,
                pr.light, pr.natural, pr.scanning ? " §e(идёт скан)" : ""));

        // «должен ли туман вообще быть виден» — оценка по горизонтальному лучу 60 блоков
        float u = layerTop - eyeY;
        float prof = u <= 0 ? 0f : (u <= thick ? u / thick
                : (float) Math.exp(-FogConfig.RAVINE_FILL_FALLOFF.get() * (u - thick)));
        float tau = pr.fac * prof * 60f;
        float alpha = 1f - (float) Math.exp(-tau * density * VolumetricFogRenderer.dbgStrength);
        say(src, String.format(Locale.ROOT,
                "   глаза Y=%.1f  внутри слоя=%s  профиль=%.2f  альфа на 60 блоках=%.2f%s",
                eyeY, (eyeY > pr.bot && eyeY < layerTop), prof, alpha,
                alpha < 0.02f ? " §c← туман тут практически невидим" : ""));

        // разбор множителя по причинам — сразу видно, кто именно съел туман
        say(src, String.format(Locale.ROOT,
                "   множитель = биом %.2f × постройки %.2f × высота_над_дном %.2f "
                        + "× склон %.2f × свет %.2f",
                pr.fSuit, pr.fBld, pr.fRel, pr.fSlope, pr.fLight));
        if (pr.fac < 0.02f) {
            String who = pr.fSuit < 0.3f ? "биом/температура/высота (maxAltitude, maxBiomeTemperature)"
                    : pr.fBld < 0.3f ? "тексель считается постройкой (не природный блок сверху)"
                    : pr.fRel < 0.3f ? "слишком высоко над «дном округи» (maxHeightAboveValley)"
                    : pr.fSlope < 0.3f ? "крутой склон (slopeDrain)"
                    : pr.fLight < 0.3f ? "разгон светом (lightClearThreshold/Strength)"
                    : "сумма факторов";
            say(src, "   §eмножитель ~0, главная причина: " + who);
        }
        if (Math.abs(layerTop - pr.raw - thick) > 0.05f) {
            say(src, String.format(Locale.ROOT,
                    "   §bверх поднят замыканием на %.1f блока над рельефом (разлом/овраг)",
                    layerTop - pr.raw - thick));
        }
    }

    private static String viewName(int v) {
        return switch (v) {
            case 0 -> "выкл";
            case 1 -> "сила тумана (красная плашка)";
            case 2 -> "дальность из depth";
            case 3 -> "положение геометрии относительно слоя";
            case 4 -> "сырые карты + depth";
            case 5 -> "источник данных: зелёный=ближний, синий=дальний, красный=заглушка";
            case 6 -> "толщина слоя (top-bot)";
            case 7 -> "накопленная оптическая глубина tau";
            case 8 -> "множитель плотности";
            default -> "?";
        };
    }

    private static void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }

    private FogDebugCommands() {}
}
