package dev.groundfog.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.groundfog.FogConfig;
import dev.groundfog.GroundFogMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Объёмный туман 2.0: полноэкранный проход после рендера мира.
 * Плотность задаётся двумя каскадами карт (FogMap); интеграл вдоль луча —
 * кусочно-аналитический (renderMode=analytic) либо raymarch.
 */
@EventBusSubscriber(modid = GroundFogMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class VolumetricFogRenderer {

    static ShaderInstance shader; // заполняется из FogShaders

    private static FogMap near;
    private static FogMap far;

    // --- снимок последнего кадра для /gfog status|probe ---
    public static float dbgStrength, dbgSlabMin, dbgSlabMax, dbgFarAvgH, dbgFarAvgOk, dbgSunDesat;
    public static final float[] dbgFogColor = new float[3];
    public static final float[] dbgAmbient = new float[3];
    public static long dbgFrames = 0;
    public static long dbgLastFrameNanos = 0;

    public static FogMap nearMap() { return near; }
    public static FogMap farMap() { return far; }

    /**
     * Дальность интегрирования тумана. Раньше в шейдере стояло жёсткое
     * maxDistance * 3 (= 288 блоков при дефолте) — туман обрывался задолго
     * до края прогруженных чанков. Теперь тянемся до render distance
     * (с запасом на диагональ), но не дальше охвата дальнего каскада:
     * за его пределами данных всё равно нет.
     */
    public static float fogFarDistance(Minecraft mc) {
        float cfg = FogDebug.or(FogDebug.maxDistance, FogConfig.MAX_DISTANCE.get());
        if (!FogConfig.FOLLOW_RENDER_DISTANCE.get()) return cfg;
        float rd = mc.options.renderDistance().get() * 16f;
        // 1.45 ~ sqrt(2): чанки грузятся квадратом, до угла дальше, чем до грани
        return Math.max(cfg, rd * 1.45f);
    }

    private static int depthCopyTex = -1;
    private static int depthFbo = -1;
    private static int depthW = -1;
    private static int depthH = -1;

    private static ClientLevel lastLevel = null;

    /** Вызывается из FogEnv каждый клиентский тик: обновление каскадов. */
    public static void tickMaps(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !level.dimensionType().hasSkyLight()) {
            if (near != null) { near.close(); near = null; }
            if (far != null) { far.close(); far = null; }
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            ClientFogState.reset();
            if (near != null) { near.close(); near = null; }
            if (far != null) { far.close(); far = null; }
        }

        // ближний каскад: 2 блока/тексель, радиус из конфига
        int nearRadius = FogConfig.NEAR_CASCADE_RADIUS.get();
        int nearSize = Mth.clamp((nearRadius * 2) / 2, 64, 256);
        if (near == null || near.sizeTex != nearSize) {
            if (near != null) near.close();
            near = new FogMap(2, nearSize, 24, 100);
        }

        // дальний каскад: весь render distance, тексель подстраивается под 256^2
        // охват дальнего каскада = 256 * texel; нужен радиус ~1.45*rd (угол
        // квадрата прогрузки) даже при смещении центра карты -> берём 2.9*rd
        int rdBlocks = mc.options.renderDistance().get() * 16;
        int farTexel = Math.max(4, Mth.ceil(rdBlocks * 2.9f / 256f));
        farTexel = (farTexel + 1) & ~1; // чётный
        if (far == null || far.texelSize != farTexel) {
            if (far != null) far.close();
            far = new FogMap(farTexel, 256, 8, 400);
        }

        if (FogDebug.freezeMaps) return;

        Vec3 pos = mc.player.position();
        near.tick(level, pos.x, pos.z);
        far.tick(level, pos.x, pos.z);
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (shader == null || near == null || !near.isReady()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        int debugView = FogDebug.view >= 0 ? FogDebug.view : FogConfig.DEBUG_VIEW.get();
        boolean debug = debugView > 0;
        float strength = FogEnv.smoothedStrength();
        if (strength <= 0.005f && !debug) return;

        if (debug && level.getGameTime() % 100 == 0) {
            GroundFogMod.LOGGER.info(
                    "[groundfog] strength={} forced={} near=({}, {}, ready={}) far=({}, {}, ready={}) stats: minRaw={} maxTop={} avgH={} avgOk={}",
                    String.format("%.3f", strength),
                    ClientFogState.isForced(level.getGameTime()),
                    near.originX(), near.originZ(), near.isReady(),
                    far == null ? 0 : far.originX(), far == null ? 0 : far.originZ(),
                    far != null && far.isReady(),
                    near.statMinRaw, near.statMaxTop, near.statAvgH,
                    String.format("%.2f", near.statAvgOk));
        }

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();

        copyDepth(mc);

        Matrix4f invProj = new Matrix4f(event.getProjectionMatrix()).invert();
        Matrix4f invView = new Matrix4f(event.getModelViewMatrix()).invert();

        float time = ((level.getGameTime() % 100000L) + partialTick) / 20.0f;
        float sunAngle = level.getSunAngle(partialTick);
        float[] fogColor = RenderSystem.getShaderFogColor();

        // --- ночной подсвет ---
        // Ванильный FogColor ночью почти чёрный (~0.02), а туман, нарисованный
        // альфа-блендом этим цветом, выглядит как чёрная клякса. Реальный туман
        // ночью — светлее фона: он рассеивает лунный/небесный свет.
        // Считаем нижний предел цвета и в шейдере берём max(FogColor, Ambient).
        float night = Mth.clamp(level.getStarBrightness(partialTick) * 2.2f, 0f, 1f);
        float moon = 0.35f + 0.65f * level.getMoonBrightness();
        float nb = FogConfig.NIGHT_BRIGHTNESS.get().floatValue() * night * moon;
        float ambR = 0.62f * nb, ambG = 0.68f * nb, ambB = 0.82f * nb;

        // --- закат ---
        // Ванильный fog color на рассвете/закате подмешивает sunrise color по
        // направлению взгляда: повернулся к солнцу — туман стал оранжевым.
        // Узнаём, насколько сейчас активна эта подкраска, и гасим её к серому.
        float sunsetAmt = 0f;
        try {
            float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
            if (sunrise != null) sunsetAmt = Mth.clamp(sunrise[3], 0f, 1f);
        } catch (Exception ignored) {
            // на всякий: кастомные dimension effects могут кидать
        }
        float sunDesat = sunsetAmt * (1.0f - FogConfig.SUN_TINT.get().floatValue());

        boolean farReady = far != null && far.isReady();
        float slabMin = near.statMinRaw;
        float slabMax = near.statMaxTop;
        float farAvgH = near.statAvgH;
        float farAvgOk = near.statAvgOk;
        if (farReady) {
            slabMin = Math.min(slabMin, far.statMinRaw);
            slabMax = Math.max(slabMax, far.statMaxTop);
            farAvgH = far.statAvgH;
            farAvgOk = far.statAvgOk;
        }

        boolean raymarch = FogConfig.RENDER_MODE.get() == FogConfig.RenderMode.RAYMARCH;

        shader.safeGetUniform("InvProjMat").set(invProj);
        shader.safeGetUniform("InvViewMat").set(invView);
        shader.safeGetUniform("CameraPos").set((float) cam.x, (float) cam.y, (float) cam.z);
        shader.safeGetUniform("GfFogColor").set(fogColor[0], fogColor[1], fogColor[2]);
        shader.safeGetUniform("GfAmbientColor").set(ambR, ambG, ambB);
        shader.safeGetUniform("SunDir").set(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0f);
        shader.safeGetUniform("NearOrigin").set((float) near.originX(), (float) near.originZ());
        shader.safeGetUniform("FarOrigin").set(
                farReady ? (float) far.originX() : 0f,
                farReady ? (float) far.originZ() : 0f);
        shader.safeGetUniform("Params0").set(
                FogDebug.or(FogDebug.density, FogConfig.DENSITY.get()),
                FogDebug.or(FogDebug.thickness, FogConfig.THICKNESS.get()),
                fogFarDistance(mc),
                FogDebug.or(FogDebug.steps, FogConfig.STEPS.get()));
        shader.safeGetUniform("Params1").set(
                strength,
                ClientFogState.isForced(level.getGameTime()) ? 1.0f : 0.0f,
                time,
                (float) debugView);
        shader.safeGetUniform("Params2").set(
                FogConfig.RAVINE_FILL_FALLOFF.get().floatValue(),
                raymarch ? 1.0f : 0.0f,
                farAvgH,
                farAvgOk);
        shader.safeGetUniform("Params3").set(
                slabMin - 1.0f,
                // запас на шум границы + на рантайм-оверрайд толщины
                slabMax + 4.0f + FogDebug.or(FogDebug.thickness, 0.0),
                (float) near.spanBlocks(),
                farReady ? (float) far.spanBlocks() : 0f);
        shader.safeGetUniform("Params4").set(
                FogDebug.noNoise ? 0.0f : 1.0f,
                FogDebug.or(FogDebug.sunGlow, FogConfig.SUN_GLOW.get()),
                sunDesat,
                FogDebug.or(FogDebug.dither, FogConfig.DITHER.get()));

        // снимок для /gfog status
        dbgStrength = strength;
        dbgSlabMin = slabMin;
        dbgSlabMax = slabMax;
        dbgFarAvgH = farAvgH;
        dbgFarAvgOk = farAvgOk;
        dbgSunDesat = sunDesat;
        dbgFogColor[0] = fogColor[0];
        dbgFogColor[1] = fogColor[1];
        dbgFogColor[2] = fogColor[2];
        dbgAmbient[0] = ambR;
        dbgAmbient[1] = ambG;
        dbgAmbient[2] = ambB;
        dbgFrames++;

        shader.setSampler("DepthSampler", depthCopyTex);
        shader.setSampler("NearMap", near.texId());
        shader.setSampler("FarMap", farReady ? far.texId() : near.texId());

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> shader);

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0f, -1.0f, 0.0f);
        bb.addVertex(1.0f, -1.0f, 0.0f);
        bb.addVertex(1.0f, 1.0f, 0.0f);
        bb.addVertex(-1.0f, 1.0f, 0.0f);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Depth buffer нельзя семплить, пока он привязан к активному framebuffer'у —
     * блитуем в отдельную текстуру из ТЕКУЩЕГО привязанного FB (Veil/Iris могут
     * подменять цель рендера). Формат аллоцируем ровно как ванильный RenderTarget:
     * blit с DEPTH_BUFFER_BIT требует точного совпадения форматов.
     */
    private static void copyDepth(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        int boundDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        if (depthCopyTex == -1 || depthW != main.width || depthH != main.height) {
            if (depthCopyTex != -1) TextureUtil.releaseTextureId(depthCopyTex);
            if (depthFbo != -1) GlStateManager._glDeleteFramebuffers(depthFbo);
            depthCopyTex = TextureUtil.generateTextureId();
            depthW = main.width;
            depthH = main.height;
            GlStateManager._bindTexture(depthCopyTex);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT,
                    depthW, depthH, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

            depthFbo = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFbo);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depthCopyTex, 0);
        }

        GL11.glGetError(); // сбросить чужую накопленную ошибку
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, boundDraw);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
        GL30.glBlitFramebuffer(0, 0, depthW, depthH, 0, 0, depthW, depthH,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        int err = GL11.glGetError();
        if (err != 0) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundDraw);
            GlStateManager._bindTexture(depthCopyTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, depthW, depthH);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundDraw);
    }

    private VolumetricFogRenderer() {}
}
