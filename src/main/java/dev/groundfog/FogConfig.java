package dev.groundfog;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Клиентский конфиг объёмного тумана (config/groundfog-client.toml).
 * См. docs/SPEC-2.0.md.
 */
public final class FogConfig {
    public static final ModConfigSpec SPEC;

    // --- рендер ---
    public static final ModConfigSpec.EnumValue<RenderMode> RENDER_MODE;
    public static final ModConfigSpec.DoubleValue DENSITY;
    public static final ModConfigSpec.DoubleValue THICKNESS;
    public static final ModConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ModConfigSpec.BooleanValue FOLLOW_RENDER_DISTANCE;
    public static final ModConfigSpec.IntValue STEPS;

    // --- рельеф / поверхность слоя ---
    public static final ModConfigSpec.IntValue RAVINE_BRIDGE_WIDTH;
    public static final ModConfigSpec.DoubleValue RAVINE_FILL_FALLOFF;
    public static final ModConfigSpec.DoubleValue SLOPE_DRAIN;
    public static final ModConfigSpec.IntValue MAX_FILL;

    // --- каскады ---
    public static final ModConfigSpec.IntValue NEAR_CASCADE_RADIUS;

    // --- ограничения (реализм) ---
    public static final ModConfigSpec.IntValue MAX_ALTITUDE;
    public static final ModConfigSpec.DoubleValue MAX_BIOME_TEMP;
    public static final ModConfigSpec.IntValue MAX_HEIGHT_ABOVE_VALLEY;
    public static final ModConfigSpec.IntValue VALLEY_FADE_RANGE;
    public static final ModConfigSpec.IntValue BIOME_EDGE_BIAS;
    public static final ModConfigSpec.BooleanValue NIGHT_FOG;
    public static final ModConfigSpec.DoubleValue NIGHT_BRIGHTNESS;

    // --- свет разгоняет туман ---
    public static final ModConfigSpec.IntValue LIGHT_CLEAR_THRESHOLD;
    public static final ModConfigSpec.DoubleValue LIGHT_CLEAR_STRENGTH;

    // --- цвет ---
    public static final ModConfigSpec.DoubleValue SUN_TINT;
    public static final ModConfigSpec.DoubleValue SUN_GLOW;
    public static final ModConfigSpec.DoubleValue DITHER;

    // --- постройки ---
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_NATURAL_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCE_BUILDING_BLOCKS;

    // --- отладка ---
    public static final ModConfigSpec.IntValue DEBUG_VIEW;

    public enum RenderMode { ANALYTIC, RAYMARCH }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("render");
        RENDER_MODE = b.comment("analytic — кусочно-аналитический интеграл (без зернистости), raymarch — старый рейм-арч")
                .defineEnum("renderMode", RenderMode.ANALYTIC);
        DENSITY = b.comment(
                "Плотность тумана (экстинкция на блок при полной силе).",
                "Рабочий диапазон 0.1..0.5. Большие значения насыщают туман на первых",
                "метрах луча: пропадает глубина, вылезает зерно интегрирования.",
                "Чтобы туман был «выше и гуще» — поднимайте thickness, а не density")
                .defineInRange("density", 0.12, 0.0, 2.0);
        THICKNESS = b.comment("Толщина слоя тумана над поверхностью, блоки")
                .defineInRange("thickness", 5.0, 1.0, 32.0);
        MAX_DISTANCE = b.comment(
                "Дальность интегрирования тумана, блоки. Дальше этой дистанции луч",
                "не считается вовсе — туман просто обрывается.",
                "При followRenderDistance=true поднимается автоматически до дальности",
                "прорисовки, так что этот параметр работает как нижняя граница")
                .defineInRange("maxDistance", 96.0, 32.0, 1024.0);
        FOLLOW_RENDER_DISTANCE = b.comment(
                "Тянуть туман до края прогруженных чанков (render distance + запас).",
                "Выключите, если нужен жёсткий предел из maxDistance ради fps")
                .define("followRenderDistance", true);
        STEPS = b.comment(
                "Шагов интегрирования: для raymarch — число сэмплов, для analytic —",
                "максимум сегментов (реально берётся ceil(длина/5 блоков), но не больше 24)")
                .defineInRange("steps", 24, 8, 64);
        b.pop();

        b.push("terrain");
        RAVINE_BRIDGE_WIDTH = b.comment("Порог разлом/обрыв, блоки: провалы уже порога перекрываются и заполняются туманом, шире — туман кончается на кромке")
                .defineInRange("ravineBridgeWidth", 16, 0, 64);
        RAVINE_FILL_FALLOFF = b.comment("Спад плотности с глубиной в залитом разломе (1/блок). Больше = быстрее редеет вглубь")
                .defineInRange("ravineFillFalloff", 0.15, 0.0, 2.0);
        SLOPE_DRAIN = b.comment("Туман «стекает» с крутых склонов: сила снижения плотности на склонах (0 = выкл)")
                .defineInRange("slopeDrain", 1.0, 0.0, 4.0);
        MAX_FILL = b.comment("Потолок заливки у построек, блоки: улица/двор между домами не заполняется выше дно+maxFill (0 = городские разломы не заливаются)")
                .defineInRange("maxFill", 12, 0, 64);
        b.pop();

        b.push("cascades");
        NEAR_CASCADE_RADIUS = b.comment("Радиус ближнего (детального) каскада карты, блоки. Уменьшайте при слабом ПК")
                .defineInRange("nearCascadeRadius", 128, 48, 256);
        b.pop();

        b.push("realism");
        MAX_ALTITUDE = b.comment("Выше этой абсолютной высоты (Y) наземный туман не образуется")
                .defineInRange("maxAltitude", 140, 0, 320);
        MAX_BIOME_TEMP = b.comment("Максимальная базовая температура биома")
                .defineInRange("maxBiomeTemperature", 1.0, 0.0, 2.0);
        MAX_HEIGHT_ABOVE_VALLEY = b.comment("Относительная отсечка: насколько точка может быть выше окружающего «дна» (блоки), прежде чем туман исчезнет")
                .defineInRange("maxHeightAboveValley", 25, 4, 128);
        VALLEY_FADE_RANGE = b.comment("Ширина плавного затухания тумана на пути к вершине, блоки")
                .defineInRange("valleyFadeRange", 10, 1, 64);
        BIOME_EDGE_BIAS = b.comment("Смещение границы с сухими биомами, блоки: +N — туман затекает в кромку пустыни, -N — отступает, затухая на влажной стороне")
                .defineInRange("biomeEdgeBias", 0, -64, 64);
        NIGHT_FOG = b.comment("Слабая дымка ночью (пик всё равно на рассвете)")
                .define("nightFog", true);
        NIGHT_BRIGHTNESS = b.comment(
                "Ночная подсветка тумана лунным светом: нижний предел яркости цвета.",
                "0 = как раньше (туман красится ванильным FogColor и ночью чернеет),",
                "0.15 = серо-голубой туман, различимый на фоне тёмного рельефа")
                .defineInRange("nightBrightness", 0.16, 0.0, 1.0);
        b.pop();

        b.push("light");
        LIGHT_CLEAR_THRESHOLD = b.comment("Порог блочного света (0-15): при свете на поверхности выше порога туман разгоняется")
                .defineInRange("lightClearThreshold", 10, 0, 15);
        LIGHT_CLEAR_STRENGTH = b.comment("Сила разгона тумана светом (0 = выкл, 1 = полный разгон при свете 15)")
                .defineInRange("lightClearStrength", 1.0, 0.0, 1.0);
        b.pop();

        b.push("color");
        SUN_TINT = b.comment(
                "Сколько ванильной «закатной» подкраски оставлять туману (0..1).",
                "Ванильный fog color на рассвете/закате красится оранжевым в зависимости",
                "от направления взгляда — из-за этого приземный туман менял цвет при повороте.",
                "0 = туман на закате нейтрально-серый, 1 = как в ванили")
                .defineInRange("sunTint", 0.35, 0.0, 1.0);
        SUN_GLOW = b.comment("Дополнительное свечение тумана в сторону солнца (прямое рассеяние)")
                .defineInRange("sunGlow", 0.25, 0.0, 2.0);
        DITHER = b.comment(
                "Пиксельный дизеринг границ сегментов интегрирования (0..1).",
                "Разбивает концентрические кольца вокруг игрока в мелкое зерно. 0 = выкл")
                .defineInRange("dither", 1.0, 0.0, 1.0);
        b.pop();

        b.push("buildings");
        EXTRA_NATURAL_BLOCKS = b.comment(
                "Дополнительные блоки, считающиеся природной поверхностью (id вида minecraft:gravel).",
                "Не природная поверхность = постройка: тумана на ней нет.",
                "Тропинка, грядка, гравий, глина, грязь и сено уже считаются природными по умолчанию")
                .defineListAllowEmpty("extraNaturalBlocks", List.of(), o -> o instanceof String);
        FORCE_BUILDING_BLOCKS = b.comment(
                "Наоборот — блоки, которые всегда считать постройкой, даже если они",
                "в списке природных по умолчанию (например minecraft:hay_block для крыш из сена)")
                .defineListAllowEmpty("forceBuildingBlocks", List.of(), o -> o instanceof String);
        b.pop();

        b.push("debug");
        DEBUG_VIEW = b.comment(
                "Отладка (лучше переключать в игре командой /gfog view):",
                "0=выкл, 1=красная плашка (яркость=сила тумана), 2=дальность из depth,",
                "3=слой vs позиция, 4=сырые карты (слева ближний каскад, справа depth),",
                "5=источник данных (зелёный ближний каскад / синий дальний / красный заглушка),",
                "6=толщина слоя top-bot, 7=накопленная оптическая глубина tau, 8=множитель плотности")
                .defineInRange("debugView", 0, 0, 8);
        b.pop();

        SPEC = b.build();
    }

    private FogConfig() {}
}
