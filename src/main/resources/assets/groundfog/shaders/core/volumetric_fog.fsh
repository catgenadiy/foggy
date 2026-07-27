#version 150

uniform sampler2D DepthSampler; // копия depth buffer'а сцены
uniform sampler2D NearMap;      // ближний каскад: RG верх (16 бит), B низ, A множитель
uniform sampler2D FarMap;       // дальний каскад, те же каналы

uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform vec3 CameraPos;
// имя не должно совпадать с ванильными default-юниформами (FogColor и т.п.)!
uniform vec3 GfFogColor;
uniform vec3 GfAmbientColor; // нижний предел цвета (лунный/небесный подсвет)
uniform vec3 SunDir;
uniform vec2 NearOrigin;
uniform vec2 FarOrigin;
uniform vec4 Params0; // x Density, y Thickness, z MaxDistance, w Steps
uniform vec4 Params1; // x FogStrength, y Forced, z Time, w DebugMode
uniform vec4 Params2; // x RavineFalloff, y RenderMode(0=analytic), z FarAvgH, w FarAvgOk
uniform vec4 Params3; // x SlabMin, y SlabMax, z NearSpan, w FarSpan
uniform vec4 Params4; // x NoiseAmount, y SunGlow, z SunsetDesat, w Dither

#define DENSITY   Params0.x
#define THICK     Params0.y
#define MAXDIST   Params0.z
#define STEPS     Params0.w
#define FOGSTR    Params1.x
#define FORCED    Params1.y
#define TIME      Params1.z
#define DEBUGM    Params1.w
#define RAVK      Params2.x
#define RMODE     Params2.y
#define FARAVGH   Params2.z
#define FARAVGOK  Params2.w
#define SLABMIN   Params3.x
#define SLABMAX   Params3.y
#define NEARSPAN  Params3.z
#define FARSPAN   Params3.w
#define NOISEAMT  Params4.x
#define SUNGLOW   Params4.y
#define SUNDESAT  Params4.z
#define DITHER    Params4.w

#define SEG_MAX 24

in vec2 texCoord;
out vec4 fragColor;

// ---------- шум ----------
float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
}

// ---------- каскады ----------
// Текстура НЕ фильтруется аппаратно: 16-битную высоту нельзя интерполировать
// побайтово. Билинейка делается вручную уже по декодированным значениям.
vec3 fetchMap(sampler2D s, ivec2 c, ivec2 sz) {
    c = clamp(c, ivec2(0), sz - ivec2(1));
    vec4 t = texelFetch(s, c, 0);
    float top = ((t.r * 255.0) * 256.0 + t.g * 255.0) / 65535.0 * 384.0 - 64.0;
    float bot = t.b * 384.0 - 64.0;
    return vec3(top, bot, t.a);
}

vec3 sampleMap(sampler2D s, vec2 uv) {
    ivec2 sz = textureSize(s, 0);
    vec2 p = uv * vec2(sz) - 0.5;
    vec2 f = fract(p);
    ivec2 i = ivec2(floor(p));
    vec3 a = fetchMap(s, i + ivec2(0, 0), sz);
    vec3 b = fetchMap(s, i + ivec2(1, 0), sz);
    vec3 c = fetchMap(s, i + ivec2(0, 1), sz);
    vec3 d = fetchMap(s, i + ivec2(1, 1), sz);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// (верх, низ, множитель, источник) слоя в точке xz мира.
// источник: 0 = средний слой-заглушка, 1 = дальний каскад, 2 = ближний
vec4 mapAtSrc(vec2 xz) {
    // запасной вариант — «средний» слой (за пределами карт)
    vec3 res = vec3(FARAVGH + THICK, FARAVGH, FARAVGOK);
    float src = 0.0;
    if (FARSPAN > 0.5) {
        vec2 uv = (xz - FarOrigin) / FARSPAN;
        if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
            vec3 m = sampleMap(FarMap, uv);
            res = vec3(m.x + THICK, m.y, m.z); // ВЕРХ СЛОЯ = рельеф + толщина
            src = 1.0;
        }
    }
    if (NEARSPAN > 0.5) {
        vec2 uv = (xz - NearOrigin) / NEARSPAN;
        vec2 mm = min(uv, 1.0 - uv);
        float w = smoothstep(0.0, 0.08, min(mm.x, mm.y)); // мягкий стык каскадов
        if (w > 0.0) {
            vec3 m = sampleMap(NearMap, uv);
            res = mix(res, vec3(m.x + THICK, m.y, m.z), w);
            src = mix(src, 2.0, step(0.5, w));
        }
    }
    return vec4(res, src);
}

vec3 mapAt(vec2 xz) { return mapAtSrc(xz).xyz; }

// ---------- аналитический интеграл ----------
// Профиль плотности по глубине u = top - y:
//   0..T          — линейный рост 0->1 (плотнее к земле)
//   T..(top-bot)  — exp(-k*(u-T)) — спад вглубь залитого разлома
// Fint — антипроизводная профиля по u.
float Fint(float u) {
    float T = max(THICK, 0.001);
    if (u <= 0.0) return 0.0;
    if (u <= T) return u * u / (2.0 * T);
    float k = max(RAVK, 1e-3);
    return T * 0.5 + (1.0 - exp(-k * (u - T))) / k;
}

float profileAt(float u) {
    float T = max(THICK, 0.001);
    if (u <= 0.0) return 0.0;
    if (u <= T) return u / T;
    return exp(-max(RAVK, 1e-3) * (u - T));
}

// Оптическая глубина сегмента [tA,tB] при локально-плоском слое m=(top,bot,fac)
float segTau(float tA, float tB, float cy, float dy, vec3 m) {
    float top = m.x, bot = m.y, fac = m.z;
    if (fac <= 0.001 || top <= bot || tB <= tA) return 0.0;
    float uCap = top - bot; // ниже низа слоя тумана нет
    if (abs(dy) < 1e-4) {
        float u = top - cy;
        if (u <= 0.0 || u >= uCap) return 0.0;
        return fac * profileAt(u) * (tB - tA);
    }
    // клип по y в [bot, top]
    float tTop = (top - cy) / dy;
    float tBot = (bot - cy) / dy;
    float lo = max(tA, min(tTop, tBot));
    float hi = min(tB, max(tTop, tBot));
    if (hi <= lo) return 0.0;
    float ua = clamp(top - (cy + dy * lo), 0.0, uCap);
    float ub = clamp(top - (cy + dy * hi), 0.0, uCap);
    return fac * abs(Fint(max(ua, ub)) - Fint(min(ua, ub))) / abs(dy);
}

// шумовая модуляция сегмента (клубление); вдали гасится
float segNoise(vec3 p, float dist) {
    if (NOISEAMT < 0.001) return 1.0; // /gfog noise 0 — чистый слой без клубления
    float n = noise3(vec3(p.x * 0.045 + TIME * 0.22, p.y * 0.08, p.z * 0.045 + TIME * 0.15));
    n = 0.55 + 0.45 * n;
    n = mix(n, 0.8, clamp(dist / 160.0, 0.0, 1.0));
    return mix(1.0, n, clamp(NOISEAMT, 0.0, 1.0));
}

vec3 heat(float x) {
    x = clamp(x, 0.0, 1.0);
    return clamp(vec3(1.5 - abs(4.0 * x - 3.0), 1.5 - abs(4.0 * x - 2.0),
                      1.5 - abs(4.0 * x - 1.0)), 0.0, 1.0);
}

void main() {
    if (DEBUGM > 3.5 && DEBUGM < 4.5) {
        // вид 4: слева ближний каскад (R=высота верха, G=множитель,
        // B=толщина слоя), справа depth
        if (texCoord.x < 0.5) {
            vec2 uv = vec2(texCoord.x * 2.0, texCoord.y);
            vec3 m = sampleMap(NearMap, uv);
            float h = (m.x - SLABMIN) / max(1.0, SLABMAX - SLABMIN);
            fragColor = vec4(clamp(h, 0.0, 1.0), m.z,
                             clamp((m.x - m.y) / 32.0, 0.0, 1.0), 0.92);
        } else {
            float d = texture(DepthSampler, vec2((texCoord.x - 0.5) * 2.0, texCoord.y)).r;
            fragColor = vec4(vec3(pow(d, 40.0)), 0.9);
        }
        return;
    }
    if (DEBUGM > 0.5 && DEBUGM < 1.5) {
        fragColor = vec4(1.0, 0.2, 0.2, 0.05 + 0.35 * FOGSTR);
        return;
    }

    float depth = texture(DepthSampler, texCoord).r;
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPos = InvProjMat * ndc;
    viewPos /= viewPos.w;
    vec3 offset = (InvViewMat * vec4(viewPos.xyz, 0.0)).xyz;
    float sceneDist = length(offset);
    vec3 dir = offset / sceneDist;

    if (DEBUGM > 1.5 && DEBUGM < 2.5) {
        fragColor = vec4(vec3(clamp(sceneDist / MAXDIST, 0.0, 1.0)), 0.85);
        return;
    }
    if (DEBUGM > 2.5 && DEBUGM < 3.5) {
        vec3 wp = CameraPos + offset;
        vec3 m = mapAt(wp.xz);
        vec3 c = wp.y < m.y - 0.5 ? vec3(1.0, 0.1, 0.1)
               : (wp.y <= m.x ? vec3(0.1, 1.0, 0.1) : vec3(0.1, 0.2, 1.0));
        fragColor = vec4(c * (0.35 + 0.65 * max(m.z, FORCED)), 0.6);
        return;
    }
    if (DEBUGM > 4.5 && DEBUGM < 5.5) {
        // вид 5: источник данных под каждым пикселем геометрии.
        // зелёный = ближний каскад, синий = дальний, красный = заглушка
        vec3 wp = CameraPos + offset;
        float src = mapAtSrc(wp.xz).w;
        vec3 c = src > 1.5 ? vec3(0.1, 1.0, 0.2)
               : (src > 0.5 ? vec3(0.2, 0.4, 1.0) : vec3(1.0, 0.1, 0.1));
        fragColor = vec4(c, 0.65);
        return;
    }
    if (DEBUGM > 5.5 && DEBUGM < 6.5) {
        // вид 6: толщина слоя (top-bot, 0..32 блока) в псевдоцвете
        vec3 wp = CameraPos + offset;
        vec3 m = mapAt(wp.xz);
        fragColor = vec4(heat((m.x - m.y) / 32.0), 0.75);
        return;
    }
    if (DEBUGM > 7.5) {
        // вид 8: множитель плотности (пригодность) прямо на геометрии
        vec3 wp = CameraPos + offset;
        vec3 m = mapAt(wp.xz);
        fragColor = vec4(heat(m.z), 0.8);
        return;
    }

    float cy = CameraPos.y;
    float dy = dir.y;
    float maxDist = min(sceneDist, MAXDIST);

    // глобальная «плита»: клип детального участка
    float tEnter = 0.0;
    float tExit = maxDist;
    if (abs(dy) > 1e-4) {
        float t0 = (SLABMIN - cy) / dy;
        float t1 = (SLABMAX - cy) / dy;
        tEnter = clamp(min(t0, t1), 0.0, maxDist);
        tExit = clamp(max(t0, t1), 0.0, maxDist);
    } else if (cy < SLABMIN || cy > SLABMAX) {
        tExit = 0.0;
    }

    float tau = 0.0;

    if (RMODE > 0.5) {
        // --- режим raymarch (сравнение/страховка) ---
        if (tExit > tEnter + 0.01) {
            int steps = int(STEPS);
            float stepLen = (tExit - tEnter) / float(steps);
            float jitter = hash13(vec3(gl_FragCoord.xy, TIME * 61.7));
            for (int i = 0; i < 64; i++) {
                if (i >= steps) break;
                vec3 p = CameraPos + dir * (tEnter + (float(i) + jitter) * stepLen);
                vec3 m = mapAt(p.xz);
                float u = m.x - p.y;
                if (u > 0.0 && u < m.x - m.y) {
                    tau += m.z * profileAt(u) * segNoise(p, tEnter + float(i) * stepLen) * stepLen;
                }
            }
        }
    } else {
        // --- аналитика: сегменты, привязанные к АБСОЛЮТНОМУ расстоянию ---
        //
        // Раньше сегменты раскладывались по отрезку [tEnter, tExit], который
        // зависит от высоты камеры и наклона луча, а их число считалось через
        // ceil(длина/5). Из-за этого:
        //   * число сегментов скачком менялось по экрану -> концентрические
        //     кольца вокруг игрока;
        //   * при подъёме/спуске вся раскладка ехала -> «силуэт» тумана
        //     двигался вместе с камерой;
        //   * граница maxDistance между основным циклом и «хвостом» давала
        //     ещё одно кольцо ровно на 96 блоках.
        // Теперь: одна сетка оболочек t_i = tFar * (i/n)^p от самой камеры,
        // одинаковая для всех лучей, плюс пиксельный дизеринг границ.
        // Степень p подбирается так, чтобы ПЕРВАЯ оболочка была ~1 блок при
        // любой дальности: p = ln(tFar)/ln(n). Иначе при большом tFar
        // (дальность прорисовки 32 чанка) первый сегмент раздувался и туман
        // под ногами считался грубее текселя карты.
        // MAXDIST уже посчитан на стороне Java с учётом render distance
        float tFar = min(sceneDist, MAXDIST);
        int nseg = int(clamp(STEPS, 6.0, float(SEG_MAX)));
        float segPow = clamp(log(max(tFar, 8.0)) / log(float(nseg)), 1.0, 3.0);
        // Амплитуда дизеринга падает с ростом плотности: при густом тумане
        // полосы всё равно не видны (всё насыщено), а вот зерно от дизеринга —
        // видно. При density по умолчанию множитель ~0.8, при density 20 — ~0.02.
        float ditherScale = 1.0 / (1.0 + DENSITY * FOGSTR * max(THICK, 1.0) * 0.4);
        float dither = (hash13(vec3(gl_FragCoord.xy, 7.0)) - 0.5) * DITHER * ditherScale;

        float prevT = 0.0;
        vec3 prevM = vec3(0.0);
        bool prevOk = false;

        for (int i = 1; i <= SEG_MAX; i++) {
            if (i > nseg) break;
            // последняя граница строго на tFar: иначе дизеринг «недоводит»
            // луч до самой геометрии и туман редеет у земли
            float x = (i == nseg) ? 1.0 : clamp((float(i) + dither) / float(nseg), 0.0, 1.0);
            float b = tFar * pow(x, segPow);
            float a = prevT;
            prevT = b;
            if (b <= a + 0.001) continue;

            // быстрый отбой: сегмент целиком выше или ниже глобальной плиты
            float ya = cy + dy * a;
            float yb = cy + dy * b;
            if (min(ya, yb) > SLABMAX || max(ya, yb) < SLABMIN) { prevOk = false; continue; }

            vec3 mB = mapAt((CameraPos + dir * b).xz);
            vec3 mA = prevOk ? prevM : mapAt((CameraPos + dir * a).xz);
            prevM = mB;
            prevOk = true;

            // слой сегмента = среднее по его концам: соседние сегменты делят
            // общую точку, поэтому tau непрерывен при сдвиге границы (нет колец)
            vec3 m = (mA + mB) * 0.5;
            float mid = (a + b) * 0.5;
            m.z *= segNoise(CameraPos + dir * mid, mid);
            tau += segTau(a, b, cy, dy, m);

            // Ранний выход по насыщению. Когда пропускание уже < 0.4%, дальние
            // сегменты не меняют картинку, но их погрешность (и дизеринг границ)
            // всё равно попадает в кадр — при высокой density это и вылезало
            // зерном. Плюс это заметно дешевле.
            if (tau * DENSITY * FOGSTR > 5.5) break;
        }
    }

    if (DEBUGM > 6.5 && DEBUGM < 7.5) {
        // вид 7: набранная оптическая глубина tau (0..3) в псевдоцвете
        fragColor = vec4(heat(tau / 3.0), 0.85);
        return;
    }

    float transmittance = exp(-tau * DENSITY * FOGSTR);
    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) discard;

    // ночью ванильный FogColor почти чёрный — поднимаем до лунного минимума
    vec3 base = max(GfFogColor, GfAmbientColor);

    // Ванильный FogColor на рассвете/закате сам подкрашивается оранжевым в
    // зависимости от направления взгляда (FogRenderer подмешивает sunrise color
    // по dot(взгляд, солнце)). Для приземного тумана это выглядит как «повернулся
    // — туман сменил цвет». Гасим эту подкраску к серому на SUNDESAT.
    float grey = dot(base, vec3(0.299, 0.587, 0.114));
    base = mix(base, vec3(grey), clamp(SUNDESAT, 0.0, 1.0));

    // собственное прямое рассеяние (мягкое, ширина шире, сила из конфига)
    float sunGlow = pow(max(dot(dir, SunDir), 0.0), 8.0);
    vec3 color = base * (1.0 + SUNGLOW * sunGlow);
    fragColor = vec4(color, alpha);
}
