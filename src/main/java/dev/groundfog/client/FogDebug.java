package dev.groundfog.client;

/**
 * Рантайм-оверрайды параметров тумана (команда /gfog). Живут только до выхода
 * из игры и НЕ пишутся в конфиг — чтобы крутить density/thickness прямо в игре
 * без перезахода и без порчи файла конфига.
 *
 * null / -1 = «брать из конфига».
 */
public final class FogDebug {

    /** Отладочный вид: -1 = из конфига, иначе 0..8. */
    public static int view = -1;

    public static Float density;
    public static Float thickness;
    public static Float maxDistance;
    public static Float steps;
    public static Float sunGlow;
    public static Float dither;

    /** Заморозить обновление каскадов (сравнивать кадры при движении). */
    public static boolean freezeMaps = false;

    /** Отключить шумовую модуляцию нельзя из конфига — а тут можно. */
    public static boolean noNoise = false;

    public static float or(Float v, double cfg) {
        return v != null ? v : (float) cfg;
    }

    public static void reset() {
        view = -1;
        density = null;
        thickness = null;
        maxDistance = null;
        steps = null;
        sunGlow = null;
        dither = null;
        freezeMaps = false;
        noNoise = false;
    }

    private FogDebug() {}
}
