# Foggy — объёмный наземный туман для Minecraft 1.21.1 (NeoForge)


Volumetric fog с привязкой к рельефу: полноэкранный проход, который «заливает»
туманом низины и долины. Слой тумана лежит на сглаженном рельефе, рассеивает
свет в сторону солнца, наползает на рассвете и тает днём.

Начиная с 2.0 оптическая глубина вдоль луча считается **аналитически**
(кусочно-замкнутый интеграл, без зернистости). Старый raymarching оставлен
как запасной режим (`renderMode = raymarch`) для сравнения и отладки —
по умолчанию он не используется.

## Стек

- **Minecraft** 1.21.1
- **NeoForge** 21.1.244
- **ModDevGradle** 1.0.23, Gradle 8.10.2, Java 21

## Сборка и запуск

```bash
./gradlew build        # jar появится в build/libs/
./gradlew runClient    # запустить клиент для теста
```

## Команды

- `/weather fog` — включить туман на 5 минут (permission level 2)
- `/weather fog <длительность>` — ванильный формат: `30s`, `2m`, `600t`
- `/weather clear` — туман плавно рассеивается, естественный туман
  подавляется на 5 минут
- `/weather rain` / `thunder` — тоже гасят принудительный туман
- `/gfog` — клиентская отладочная консоль: `status`, `probe`, `rescan`,
  `view 0..8` (визуализация каскадов, толщины слоя, tau и т.д.), `set`, `freeze`

## Как устроен рендер

1. **`VolumetricFogRenderer`** подписан на `RenderLevelStageEvent.AFTER_LEVEL`:
   после рендера мира рисуется полноэкранный квад с кастомным core-шейдером.
2. **Depth buffer** основного framebuffer'а копируется в отдельную текстуру
   (семплить привязанный depth нельзя) — из него шейдер восстанавливает
   мировую позицию каждого пикселя через обратные матрицы проекции и вида.
3. **Карта высот** (`FogMap`) — два каскада, которые CPU сканирует порциями
   по строкам:
   - ближний: 2 блока/тексель, радиус `nearCascadeRadius` (по умолчанию
     128 блоков);
   - дальний: 256 текселей, размер текселя подбирается под дальность
     прорисовки, обновляется реже.
   Высота кодируется в 16 бит (R+G) с ручной билинейной фильтрацией в шейдере.
   Поверх скана — морфологическая обработка: постройки не «продавливают»
   дыры в тумане, узкие разломы (`ravineBridgeWidth`) перекрываются и
   заливаются, с крутых склонов туман «стекает» (`slopeDrain`).
4. **Аналитический интегратор**: луч разбивается на сегменты по абсолютной
   дистанции от камеры, в каждом оптическая глубина слоя (низ/верх из карты
   высот) берётся в замкнутой форме. Пер-пиксельный дизеринг прячет остатки
   бандинга. Верхняя граница слоя — *сглаженный* рельеф + `thickness`,
   поэтому туман заливает низины ровным «озером», а не повторяет каждый
   бугорок.
5. Цвет тумана берётся из ванильного fog color (закатный оттенок приглушается
   через `sunTint`), плюс форвард-скаттеринг в сторону солнца (`sunGlow`);
   ночью туман подсвечивается `nightBrightness`, а не чернеет.
6. Сила тумана (время суток, погода, команды) сглаживается на CPU —
   переходы занимают несколько секунд.

## Ограничения для реализма

- пик на рассвете, днём тумана нет, ночью слабая дымка (`nightFog`)
- только влажные биомы (`hasPrecipitation`), порог температуры биома
- потолок по абсолютной высоте `maxAltitude` (туман — явление долин)
- относительная отсечка: выше окружающего «дна» долины чем на
  `maxHeightAboveValley` блоков туман плавно исчезает
- освещённые места (факелы и т.п.) разгоняют туман (секция `light`)
- гроза почти разгоняет туман, дождь ослабляет
- под землёй и в пещерах тумана нет (проверка высоты рельефа)
- при `/weather fog` фильтры биомов снимаются, но привязка к рельефу остаётся

## Конфиг (`config/groundfog-client.toml`)

Основные секции:

- `render` — `renderMode` (analytic/raymarch), `density` (экстинкция на блок,
  рабочий диапазон 0.1..0.5; для «гуще» крутите не её, а `thickness`),
  `thickness`, `maxDistance`, `followRenderDistance`, `steps`
- `terrain` — `ravineBridgeWidth`, `ravineFillFalloff`, `slopeDrain`, `maxFill`
- `cascades` — `nearCascadeRadius`
- `realism` — `maxAltitude`, `maxBiomeTemperature`, `maxHeightAboveValley`,
  `valleyFadeRange`, `biomeEdgeBias`, `nightFog`, `nightBrightness`
- `light` — `lightClearThreshold`, `lightClearStrength`
- `color` — `sunTint`, `sunGlow`, `dither`
- `buildings` — `extraNaturalBlocks`, `forceBuildingBlocks`
- `debug` — `debugView`

## Структура

```
src/main/java/dev/groundfog/
  GroundFogMod.java     — входная точка, конфиг, сеть
  FogConfig.java        — клиентский конфиг
  FogCommands.java      — /weather fog, перехват /weather clear
  net/ForcedFogPayload.java — пакет сервер->клиент
  client/
    FogShaders.java     — регистрация core-шейдера
    FogEnv.java         — сила тумана (время/погода) + сглаживание
    ClientFogState.java — состояние /weather fog на клиенте
    FogMap.java         — скан мира, каскады, морфология, кодирование текстуры
    VolumetricFogRenderer.java — depth-копия, каскады, полноэкранный проход
    FogDebug.java, FogDebugCommands.java — /gfog: status/probe/view/…
src/main/resources/assets/groundfog/shaders/core/
  volumetric_fog.json / .vsh / .fsh
```
