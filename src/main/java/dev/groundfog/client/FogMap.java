package dev.groundfog.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import dev.groundfog.FogConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Каскад карты тумана: скан мира на CPU + обработка (морфологическое
 * замыкание разломов, потолок заливки у построек, относительные высоты,
 * блюр пригодности, свет) + текстура для шейдера.
 *
 * Каналы текстуры: R = верх слоя, G = множитель плотности (0..1),
 * B = низ слоя (настоящий рельеф). Высоты кодируются (y+64)/384.
 */
public final class FogMap {

    private static final float INVALID = -1.0e6f;

    public final int texelSize;
    public final int sizeTex;
    private final int rowsPerTick;
    private final int refreshPeriodTicks;

    // origin отображаемых данных (для юниформов) и скана
    private int dispOx = Integer.MIN_VALUE, dispOz = Integer.MIN_VALUE;
    private int pendOx, pendOz;
    private int scanRow = -1; // -1 = скан не идёт
    private long lastScanFinish = Long.MIN_VALUE;
    private boolean ready = false;

    // staging (заполняется сканом)
    private final float[] rawH;
    private final boolean[] natural;
    private final float[] suit;
    private final float[] light;

    // рабочие буферы обработки
    private final float[] bufA;
    private final float[] bufB;

    private DynamicTexture tex;
    private List<Block> extraNatural = List.of();
    private List<Block> forcedBuilding = List.of();

    // статистика для шейдера
    public float statMinRaw = 62, statMaxTop = 80, statAvgH = 64, statAvgOk = 0;

    // снимок последнего результата обработки — только для отладки (/gfog probe|dump)
    private final float[] dbgTop;
    private final float[] dbgFac;
    private final float[] dbgValley;
    private final float[] dbgLight;
    private final int[] dbgFactors; // байты: пригодность, постройки, отн.высота, склон
    public long lastProcessNanos = 0;

    public FogMap(int texelSize, int sizeTex, int rowsPerTick, int refreshPeriodTicks) {
        this.texelSize = texelSize;
        this.sizeTex = sizeTex;
        this.rowsPerTick = rowsPerTick;
        this.refreshPeriodTicks = refreshPeriodTicks;
        int n = sizeTex * sizeTex;
        rawH = new float[n];
        natural = new boolean[n];
        suit = new float[n];
        light = new float[n];
        bufA = new float[n];
        bufB = new float[n];
        dbgTop = new float[n];
        dbgFac = new float[n];
        dbgValley = new float[n];
        dbgLight = new float[n];
        dbgFactors = new int[n];
    }

    private static int pack4(float a, float b, float c, float d) {
        return (Mth.clamp((int) (a * 255f), 0, 255) << 24)
                | (Mth.clamp((int) (b * 255f), 0, 255) << 16)
                | (Mth.clamp((int) (c * 255f), 0, 255) << 8)
                | Mth.clamp((int) (d * 255f), 0, 255);
    }

    public boolean isReady() { return ready && tex != null; }
    public int texId() { return tex == null ? 0 : tex.getId(); }
    public int originX() { return dispOx; }
    public int originZ() { return dispOz; }
    public int spanBlocks() { return sizeTex * texelSize; }

    public void close() {
        if (tex != null) { tex.close(); tex = null; }
        ready = false;
    }

    public void tick(ClientLevel level, double camX, double camZ) {
        int span = spanBlocks();
        long now = level.getGameTime();

        if (scanRow < 0) {
            int cx = Mth.floor(camX), cz = Mth.floor(camZ);
            boolean moved = dispOx == Integer.MIN_VALUE
                    || Math.abs(cx - (dispOx + span / 2)) > span / 6
                    || Math.abs(cz - (dispOz + span / 2)) > span / 6;
            boolean stale = now - lastScanFinish > refreshPeriodTicks;
            if (moved || stale) {
                pendOx = (cx & ~15) - span / 2;
                pendOz = (cz & ~15) - span / 2;
                scanRow = 0;
                reloadExtraNatural();
            }
            if (scanRow < 0) return;
        }

        // скан порции строк
        int end = Math.min(sizeTex, scanRow + rowsPerTick);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int j = scanRow; j < end; j++) {
            scanRowInto(level, j, pos);
        }
        scanRow = end;

        if (scanRow >= sizeTex) {
            scanRow = -1;
            lastScanFinish = now;
            long t0 = System.nanoTime();
            process(level);
            lastProcessNanos = System.nanoTime() - t0;
            dispOx = pendOx;
            dispOz = pendOz;
            ready = true;
        }
    }

    private void reloadExtraNatural() {
        extraNatural = resolve(FogConfig.EXTRA_NATURAL_BLOCKS.get());
        forcedBuilding = resolve(FogConfig.FORCE_BUILDING_BLOCKS.get());
    }

    private static List<Block> resolve(List<? extends String> ids) {
        List<Block> list = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(list::add);
            }
        }
        return list;
    }

    /**
     * Блоки, которые тегами не покрыты, но по смыслу — земля, а не постройка.
     * Тропинка (dirt_path) НЕ входит в тег minecraft:dirt, грядка, гравий,
     * глина и сено — тоже нигде: из-за этого над ними была дыра в тумане.
     */
    private static boolean isGroundLike(BlockState bs) {
        return bs.is(Blocks.DIRT_PATH)
                || bs.is(Blocks.FARMLAND)
                || bs.is(Blocks.GRAVEL)
                || bs.is(Blocks.CLAY)
                || bs.is(Blocks.MUD)
                || bs.is(Blocks.PACKED_MUD)
                || bs.is(Blocks.MUDDY_MANGROVE_ROOTS)
                || bs.is(Blocks.ROOTED_DIRT)
                || bs.is(Blocks.MOSS_BLOCK)
                || bs.is(Blocks.HAY_BLOCK)
                || bs.is(Blocks.SNOW)
                || bs.is(Blocks.POWDER_SNOW);
    }

    private void scanRowInto(ClientLevel level, int j, BlockPos.MutableBlockPos pos) {
        int minY = level.getMinBuildHeight();
        float maxTemp = FogConfig.MAX_BIOME_TEMP.get().floatValue();
        int maxAlt = FogConfig.MAX_ALTITUDE.get();
        int half = texelSize / 2;

        for (int i = 0; i < sizeTex; i++) {
            int idx = j * sizeTex + i;
            int wx = pendOx + i * texelSize + half;
            int wz = pendOz + j * texelSize + half;

            // клиенту синкается только MOTION_BLOCKING (NO_LEAVES на клиенте нет!)
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
            if (h <= minY + 1) { // чанк не прогружен
                rawH[idx] = INVALID;
                natural[idx] = false;
                suit[idx] = 0f;
                light[idx] = 0f;
                continue;
            }

            // спуск сквозь кроны к земле. Через воздух/стволы идём только если
            // выше была листва — иначе крыша из брёвен «пропустила» бы нас внутрь дома
            boolean seenLeaves = false;
            int guard = 0;
            while (h > minY && guard++ < 48) {
                BlockState bs = level.getBlockState(pos.set(wx, h - 1, wz));
                if (bs.is(BlockTags.LEAVES)) { seenLeaves = true; h--; }
                else if (seenLeaves && (bs.isAir() || bs.is(BlockTags.LOGS))) h--;
                else break;
            }

            BlockState ground = level.getBlockState(pos.set(wx, h - 1, wz));
            boolean nat = !level.getFluidState(pos).isEmpty() // вода/лава — «природа»
                    || ground.is(BlockTags.DIRT)
                    || ground.is(BlockTags.SAND)
                    || ground.is(BlockTags.BASE_STONE_OVERWORLD)
                    || ground.is(BlockTags.SNOW)
                    || ground.is(BlockTags.ICE)
                    || ground.is(BlockTags.LEAVES)
                    || isGroundLike(ground)
                    || isIn(extraNatural, ground);
            if (isIn(forcedBuilding, ground)) nat = false;
            // брёвна намеренно НЕ природные: крыша из брёвен = постройка

            pos.set(wx, h, wz);
            Biome biome = level.getBiome(pos).value();
            boolean ok = h <= maxAlt
                    && biome.hasPrecipitation()
                    && biome.getBaseTemperature() <= maxTemp;

            rawH[idx] = h;
            natural[idx] = nat;
            suit[idx] = ok ? 1f : 0f;
            light[idx] = level.getBrightness(LightLayer.BLOCK, pos);
        }
    }

    private static boolean isIn(List<Block> list, BlockState bs) {
        for (Block bl : list) {
            if (bs.is(bl)) return true;
        }
        return false;
    }

    /** Обработка: замыкание, потолок заливки, склоны, отн. высоты, блюр, свет. */
    private void process(ClientLevel level) {
        int n = sizeTex * sizeTex;
        float thickness = FogConfig.THICKNESS.get().floatValue();
        int closeR = Math.max(0, Math.round(FogConfig.RAVINE_BRIDGE_WIDTH.get() / 2f / texelSize));
        int maxFill = FogConfig.MAX_FILL.get();
        float slopeDrain = FogConfig.SLOPE_DRAIN.get().floatValue();
        int relMax = FogConfig.MAX_HEIGHT_ABOVE_VALLEY.get();
        int relFade = FogConfig.VALLEY_FADE_RANGE.get();
        int biasTexels = Math.round(FogConfig.BIOME_EDGE_BIAS.get() / (float) texelSize);
        int lightThr = FogConfig.LIGHT_CLEAR_THRESHOLD.get();
        float lightStr = FogConfig.LIGHT_CLEAR_STRENGTH.get().floatValue();

        // --- верх слоя: морфологическое замыкание природного рельефа ---
        // постройки и непрогруженное = INVALID: не служат опорами мостов
        for (int k = 0; k < n; k++) {
            bufA[k] = (rawH[k] > INVALID + 1 && natural[k]) ? rawH[k] : INVALID;
        }
        float[] top = new float[n]; // отдельный массив: bufA/bufB ниже переиспользуются
        if (closeR > 0) {
            morph(bufA, bufB, closeR, true);  // dilate
            morph(bufB, top, closeR, false);  // erode
        } else {
            System.arraycopy(bufA, 0, top, 0, n);
        }
        // замыкание не может опустить поверхность ниже рельефа; там, где опор
        // не было (постройки/дыры), берём сам рельеф
        for (int k = 0; k < n; k++) {
            float raw = rawH[k];
            if (raw <= INVALID + 1) { top[k] = INVALID; continue; }
            if (top[k] <= INVALID + 1) top[k] = raw;
            top[k] = Math.max(top[k], raw);
        }

        // --- потолок заливки рядом с постройками ---
        if (closeR > 0 && maxFill >= 0) {
            // маска «рядом с постройкой» = дилатация маски построек
            for (int k = 0; k < n; k++) {
                bufA[k] = (rawH[k] > INVALID + 1 && !natural[k]) ? 1f : 0f;
            }
            float[] nearBld = new float[n];
            morph(bufA, nearBld, closeR, true);
            for (int k = 0; k < n; k++) {
                if (nearBld[k] > 0.5f && rawH[k] > INVALID + 1) {
                    top[k] = Math.min(top[k], rawH[k] + Math.max(thickness, maxFill));
                }
            }
        }

        // лёгкое сглаживание верха (склоны — непрерывный ковёр)
        blur(top, bufA, 1);
        System.arraycopy(bufA, 0, top, 0, n);

        // --- относительная высота: «дно» округи = широкая эрозия рельефа ---
        int valleyR = Math.max(1, 40 / texelSize);
        for (int k = 0; k < n; k++) bufA[k] = rawH[k] > INVALID + 1 ? rawH[k] : 1.0e6f;
        float[] valley = new float[n];
        morph(bufA, valley, valleyR, false); // erode = локальный минимум
        blur(valley, bufA, 2);
        System.arraycopy(bufA, 0, valley, 0, n);

        // --- пригодность: биомный сдвиг границы + широкий блюр ---
        System.arraycopy(suit, 0, bufA, 0, n);
        if (biasTexels != 0) {
            morph(bufA, bufB, Math.abs(biasTexels), biasTexels > 0); // + dilate / - erode
            System.arraycopy(bufB, 0, bufA, 0, n);
        }
        blur(bufA, bufB, Math.max(2, 8 / texelSize));
        float[] suitBlur = bufB; // дальше bufB занят

        // --- маска построек: морфологическое ОТКРЫТИЕ ---
        // Раньше любой не-природный тексель обнулял туман. На грядках жителей
        // это дырявило туман по краям: бревенчатые столбики, компостеры,
        // калитки, факелы на заборе — каждый такой тексель = дыра 2 блока
        // (плюс ещё по текселю в стороны из-за билинейки).
        // Открытие (erode -> dilate) убирает одиночные пятна, оставляя
        // настоящие постройки (крыша дома — крупный массив, он выживает).
        int openR = texelSize <= 3 ? 1 : 0;
        float[] bld = new float[n];
        for (int k = 0; k < n; k++) {
            bufA[k] = (rawH[k] > INVALID + 1 && !natural[k]) ? 1f : 0f;
        }
        if (openR > 0) {
            float[] tmp = new float[n];
            morph(bufA, tmp, openR, false); // erode: съедает мелочь
            morph(tmp, bld, openR, true);   // dilate: возвращает размер домам
        } else {
            System.arraycopy(bufA, 0, bld, 0, n);
        }
        blur(bld, bufA, 1); // мягкий край вместо ступеньки
        System.arraycopy(bufA, 0, bld, 0, n);

        // --- разгон тумана светом, сглаженный ---
        // точечный факел давал ровно один «прокол» в текселе; блюр превращает
        // его в мягкое пятно
        float[] lightClear = new float[n];
        for (int k = 0; k < n; k++) {
            float cl = 0f;
            if (lightStr > 0f && light[k] > lightThr) {
                cl = (light[k] - lightThr) / Math.max(1f, 15f - lightThr);
                cl = Math.min(1f, cl * lightStr);
            }
            lightClear[k] = cl;
        }
        blur(lightClear, bufA, 1);
        System.arraycopy(bufA, 0, lightClear, 0, n);

        // --- итоговый множитель плотности + статистика ---
        float minRaw = Float.MAX_VALUE, maxTop = -Float.MAX_VALUE;
        double sumH = 0, sumOk = 0;
        int cnt = 0;

        // средняя высота валидных текселей нужна ДО записи пикселей: дыры
        // (непрогруженные чанки) пишем с этой высотой и нулевым множителем,
        // иначе ручная билинейка в шейдере утащит верх слоя к -64
        double preSum = 0;
        int preCnt = 0;
        for (int k = 0; k < n; k++) {
            if (rawH[k] > INVALID + 1) { preSum += rawH[k]; preCnt++; }
        }
        float holeH = preCnt > 0 ? (float) (preSum / preCnt) : level.getSeaLevel();

        if (tex == null) {
            tex = new DynamicTexture(sizeTex, sizeTex, false);
            // NEAREST: 16-битную высоту (R=старший байт, G=младший) нельзя
            // интерполировать аппаратно — билинейка вручную в шейдере
            tex.setFilter(false, false);
        }
        NativeImage img = tex.getPixels();
        if (img == null) return;

        for (int j = 0; j < sizeTex; j++) {
            for (int i = 0; i < sizeTex; i++) {
                int k = j * sizeTex + i;
                float raw = rawH[k];
                if (raw <= INVALID + 1) {
                    img.setPixelRGBA(i, j, packTexel(holeH, holeH, 0f));
                    dbgTop[k] = holeH;
                    dbgFac[k] = 0f;
                    dbgValley[k] = holeH;
                    dbgFactors[k] = 0;
                    dbgLight[k] = 0f;
                    continue;
                }
                float fSuit = suitBlur[k];
                float fBld = 1f - Mth.clamp(bld[k], 0f, 1f); // постройки

                // плавное затухание к вершинам (относительная высота)
                float rel = raw - valley[k];
                float fRel = 1f - smoothstep(relMax - relFade, relMax, rel);

                // «стекание» со склонов: градиент верхней поверхности
                float fSlope = 1f;
                if (slopeDrain > 0f) {
                    // fallback = сам тексель: раньше дыра в карте читалась как 0
                    // и давала «обрыв» в 70 блоков → туман исчезал у края карты
                    float c0 = top[k];
                    float gx = sample(top, i + 1, j, c0) - sample(top, i - 1, j, c0);
                    float gz = sample(top, i, j + 1, c0) - sample(top, i, j - 1, c0);
                    float slope = (float) Math.sqrt(gx * gx + gz * gz) / (2f * texelSize);
                    fSlope = 1f / (1f + slopeDrain * slope * 2f);
                }

                // свет разгоняет туман (уже сглажен)
                float fLight = 1f - Mth.clamp(lightClear[k], 0f, 1f);

                float f = fSuit * fBld * fRel * fSlope * fLight;
                dbgFactors[k] = pack4(fSuit, fBld, fRel, fSlope);
                dbgLight[k] = fLight;

                float t = top[k];
                f = Mth.clamp(f, 0f, 1f);
                img.setPixelRGBA(i, j, packTexel(t, raw, f));
                dbgTop[k] = t;
                dbgFac[k] = f;
                dbgValley[k] = valley[k];

                minRaw = Math.min(minRaw, raw);
                maxTop = Math.max(maxTop, t);
                sumH += raw;
                sumOk += f;
                cnt++;
            }
        }
        tex.upload();

        if (cnt > 0) {
            statMinRaw = minRaw;
            statMaxTop = maxTop + thickness;
            statAvgH = (float) (sumH / cnt);
            statAvgOk = (float) (sumOk / cnt);
        } else {
            statMinRaw = level.getSeaLevel() - 2;
            statMaxTop = level.getSeaLevel() + 16;
            statAvgH = level.getSeaLevel();
            statAvgOk = 0f;
        }
    }

    /**
     * Упаковка текселя: R+G = верх слоя (16 бит на диапазон -64..320, шаг
     * 0.006 блока), B = рельеф (8 бит, шаг 1.5 блока), A = множитель.
     * Раньше верх хранился в 8 битах — шаг 1.5 блока давал ступеньки на
     * поверхности тумана и «неточное повторение рельефа».
     * Порядок в int для NativeImage — ABGR.
     */
    private static int packTexel(float top, float bot, float fac) {
        int t16 = Mth.clamp(Math.round((top + 64f) * 65535f / 384f), 0, 65535);
        int r = (t16 >> 8) & 0xFF;
        int g = t16 & 0xFF;
        int b = encodeH8(bot);
        int a = Mth.clamp(Math.round(fac * 255f), 0, 255);
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int encodeH8(float y) {
        return Mth.clamp((int) ((y + 64f) * 255f / 384f), 0, 255);
    }

    private float sample(float[] a, int i, int j, float fallback) {
        i = Mth.clamp(i, 0, sizeTex - 1);
        j = Mth.clamp(j, 0, sizeTex - 1);
        float v = a[j * sizeTex + i];
        return v <= INVALID + 1 ? fallback : v;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Mth.clamp((v - e0) / Math.max(1e-4f, e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Сепарабельная морфология: dilate (max) или erode (min) квадратом радиуса r. */
    private void morph(float[] src, float[] dst, int r, boolean dilate) {
        int s = sizeTex;
        // горизонталь: src -> dst
        for (int j = 0; j < s; j++) {
            int row = j * s;
            for (int i = 0; i < s; i++) {
                float v = dilate ? -Float.MAX_VALUE : Float.MAX_VALUE;
                int lo = Math.max(0, i - r), hi = Math.min(s - 1, i + r);
                for (int x = lo; x <= hi; x++) {
                    float c = src[row + x];
                    v = dilate ? Math.max(v, c) : Math.min(v, c);
                }
                dst[row + i] = v;
            }
        }
        // вертикаль: dst -> dst (через временный проход по столбцам)
        float[] col = new float[s];
        for (int i = 0; i < s; i++) {
            for (int j = 0; j < s; j++) col[j] = dst[j * s + i];
            for (int j = 0; j < s; j++) {
                float v = dilate ? -Float.MAX_VALUE : Float.MAX_VALUE;
                int lo = Math.max(0, j - r), hi = Math.min(s - 1, j + r);
                for (int y = lo; y <= hi; y++) {
                    float c = col[y];
                    v = dilate ? Math.max(v, c) : Math.min(v, c);
                }
                dst[j * s + i] = v;
            }
        }
    }

    // ==================== ОТЛАДКА ====================

    /** Значения карты в одной точке мира — для /gfog probe. */
    public static final class Probe {
        public boolean inMap;
        public boolean scanning;
        public int texelX, texelZ;
        public float raw, top, bot, fac, valley, light;
        public boolean natural;
        /** Разбор множителя по причинам: пригодность, постройки, отн. высота, склон, свет. */
        public float fSuit, fBld, fRel, fSlope, fLight;
    }

    /** Форсировать пересканирование карты со следующего тика. */
    public void forceRescan() {
        lastScanFinish = Long.MIN_VALUE;
    }

    public boolean isScanning() { return scanRow >= 0; }

    public Probe probe(double wx, double wz) {
        Probe p = new Probe();
        p.scanning = scanRow >= 0;
        if (dispOx == Integer.MIN_VALUE) return p;
        int i = Mth.floor((wx - dispOx) / texelSize);
        int j = Mth.floor((wz - dispOz) / texelSize);
        if (i < 0 || j < 0 || i >= sizeTex || j >= sizeTex) return p;
        int k = j * sizeTex + i;
        p.inMap = true;
        p.texelX = i;
        p.texelZ = j;
        p.raw = rawH[k] <= INVALID + 1 ? Float.NaN : rawH[k];
        p.top = dbgTop[k];
        p.bot = rawH[k] <= INVALID + 1 ? Float.NaN : rawH[k];
        p.fac = dbgFac[k];
        p.valley = dbgValley[k];
        p.light = light[k];
        p.natural = natural[k];
        int fx = dbgFactors[k];
        p.fSuit = ((fx >>> 24) & 0xFF) / 255f;
        p.fBld = ((fx >>> 16) & 0xFF) / 255f;
        p.fRel = ((fx >>> 8) & 0xFF) / 255f;
        p.fSlope = (fx & 0xFF) / 255f;
        p.fLight = dbgLight[k];
        return p;
    }

    /**
     * Сохранить визуализацию карты в PNG: R — верх слоя (нормирован по
     * min/max карты), G — множитель плотности, B — толщина верх-рельеф/32.
     * Чёрный пиксель = дыра (чанк не прогружен).
     */
    public void dumpPng(java.nio.file.Path file) throws java.io.IOException {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int k = 0; k < sizeTex * sizeTex; k++) {
            if (rawH[k] <= INVALID + 1) continue;
            lo = Math.min(lo, rawH[k]);
            hi = Math.max(hi, dbgTop[k]);
        }
        float rng = Math.max(1f, hi - lo);
        try (NativeImage out = new NativeImage(sizeTex, sizeTex, false)) {
            for (int j = 0; j < sizeTex; j++) {
                for (int i = 0; i < sizeTex; i++) {
                    int k = j * sizeTex + i;
                    if (rawH[k] <= INVALID + 1) {
                        out.setPixelRGBA(i, j, 0xFF000000);
                        continue;
                    }
                    int r = Mth.clamp((int) ((dbgTop[k] - lo) / rng * 255f), 0, 255);
                    int g = Mth.clamp((int) (dbgFac[k] * 255f), 0, 255);
                    int b = Mth.clamp((int) ((dbgTop[k] - rawH[k]) / 32f * 255f), 0, 255);
                    out.setPixelRGBA(i, j, 0xFF000000 | (b << 16) | (g << 8) | r);
                }
            }
            out.writeToFile(file);
        }
    }

    /** Сепарабельный box-блюр радиуса r (INVALID пропускается). */
    private void blur(float[] src, float[] dst, int r) {
        int s = sizeTex;
        for (int j = 0; j < s; j++) {
            int row = j * s;
            for (int i = 0; i < s; i++) {
                float sum = 0; int c = 0;
                int lo = Math.max(0, i - r), hi = Math.min(s - 1, i + r);
                for (int x = lo; x <= hi; x++) {
                    float v = src[row + x];
                    if (v > INVALID + 1 && v < 0.9e6f) { sum += v; c++; }
                }
                dst[row + i] = c > 0 ? sum / c : src[row + i];
            }
        }
        float[] col = new float[s];
        for (int i = 0; i < s; i++) {
            for (int j = 0; j < s; j++) col[j] = dst[j * s + i];
            for (int j = 0; j < s; j++) {
                float sum = 0; int c = 0;
                int lo = Math.max(0, j - r), hi = Math.min(s - 1, j + r);
                for (int y = lo; y <= hi; y++) {
                    float v = col[y];
                    if (v > INVALID + 1 && v < 0.9e6f) { sum += v; c++; }
                }
                dst[j * s + i] = c > 0 ? sum / c : col[j];
            }
        }
    }
}
