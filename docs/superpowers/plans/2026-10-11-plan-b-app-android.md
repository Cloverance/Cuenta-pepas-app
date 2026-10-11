# Plan B — App Android Cuentapepas v1

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android offline que detecta, sigue y cuenta pepas de café en video en vivo, por madurez, por planta y por recorrido. Avisa sobre la calidad de la captura, mide el rendimiento en el dispositivo, calibra el factor visibles→reales y estima la cosecha por lote con intervalos y cobertura.

**Architecture:** Proyecto Gradle en `android/` con dos módulos:
- **`:core`**: Kotlin/JVM puro con toda la lógica (posprocesado, tracker, contador, calidad, calibración, estimación, sesiones), probada con JUnit en el PC.
- **`:app`**: Android con Compose, CameraX, TFLite y GPS. Solo orquesta y muestra; no contiene lógica de negocio que se pueda probar en JVM.

El contrato con el modelo es `android/app/src/main/assets/model/model.json`, que produce el Plan A, Task A6.

**Tech Stack:** Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0 (con el plugin del compilador de Compose), JDK 17, compileSdk/targetSdk 35, minSdk 26. Compose BOM 2024.12.01, Material3, Navigation Compose 2.8.5, CameraX 1.4.1, TensorFlow Lite 2.16.1 (+gpu, +gpu-api), kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0, JUnit 4.13.2. Todas son Apache-2.0 o EPL/BSD; ninguna es AGPL ni GPL.

**Reglas para todas las tareas:**
- Cada comando arranca con `cd "/d/Programas Data/Cuenta Pepas" && source tools/env.sh`. Desde un worktree: `export CP_TOOLS="/d/Programas Data/Cuenta Pepas/.tools"; source tools/env.sh`.
- Gradle se ejecuta con `cd android && ./gradlew --no-daemon <tarea>`.
- **Nada se instala ni se escribe en C:.** Todo usa `GRADLE_USER_HOME` y `ANDROID_HOME` en `.tools/`.
- El código y los identificadores van en inglés. **Los textos de la interfaz van en español**, en `res/values/strings.xml`.
- Paquete: `app.cuentapepas` (`app.cuentapepas.core.*` en `:core`).
- Los commits terminan con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Honestidad numérica:** ninguna pantalla muestra una cifra de cosecha sin su intervalo. Mientras no haya calibración, se dice "pepas visibles". "Sin dato" nunca se convierte en 0.

---

## Estructura de archivos

```
android/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew  gradlew.bat  gradle/wrapper/*
  core/build.gradle.kts
  core/src/main/kotlin/app/cuentapepas/core/
    geometry/Box.kt
    detect/Detection.kt  detect/ModelSpec.kt  detect/YoloxPostprocessor.kt  detect/Letterbox.kt
    track/KalmanFilterXyah.kt  track/LinearAssignment.kt  track/STrack.kt  track/ByteTracker.kt
    count/ClassCounts.kt  count/TrackCounter.kt
    quality/LumaImage.kt  quality/QualityMetrics.kt  quality/GlobalMotionEstimator.kt  quality/QualityAdvisor.kt
    stats/Bootstrap.kt  stats/Interval.kt
    calibration/RatioCalibrator.kt
    estimate/LotEstimator.kt
    session/Models.kt  session/PassResolver.kt  session/Geo.kt
    perf/PerfStats.kt
  core/src/test/kotlin/app/cuentapepas/core/...   (una prueba por archivo)
  core/src/test/resources/fixtures/               (del Plan A, Task A7)
  app/build.gradle.kts  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/assets/model/{model.json, *.tflite}   app/src/main/assets/bench/*.jpg
  app/src/main/res/values/strings.xml  themes.xml  colors.xml   res/xml/file_paths.xml
  app/src/main/kotlin/app/cuentapepas/
    MainActivity.kt  CuentapepasApp.kt (Application + ServiceLocator)
    data/SessionStore.kt  data/SettingsStore.kt  data/Exporter.kt
    device/DeviceMonitor.kt  device/LocationRecorder.kt
    inference/TfliteDetector.kt  inference/FramePreprocessor.kt
    pipeline/FrameProcessor.kt  pipeline/EvaluationLogger.kt
    camera/CameraController.kt
    ui/nav/AppNav.kt  ui/theme/Theme.kt
    ui/home/HomeScreen.kt  ui/home/LotEditDialog.kt
    ui/newsession/NewSessionScreen.kt
    ui/live/LiveScreen.kt  ui/live/LiveViewModel.kt  ui/live/DetectionOverlay.kt
    ui/summary/SessionSummaryScreen.kt
    ui/calibration/CalibrationScreen.kt
    ui/estimate/LotEstimateScreen.kt
    ui/bench/BenchmarkScreen.kt
    ui/settings/SettingsScreen.kt  ui/about/AboutScreen.kt
```

---

### Task B1: Esqueleto Gradle que compila

**Files:** todo lo que va bajo `android/` en el nivel raíz, más `core/build.gradle.kts`, `app/build.gradle.kts`, un `MainActivity.kt` mínimo, el manifiesto, los recursos y `core/src/test/kotlin/app/cuentapepas/core/SanityTest.kt`.

- [ ] **Step 1: Wrapper de Gradle.**
  1. Descarga `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` a `.tools/downloads/` y descomprímelo en `.tools/gradle-8.11.1/`.
  2. Desde `android/`, corre `"$CP_TOOLS/gradle-8.11.1/bin/gradle" wrapper --gradle-version 8.11.1 --distribution-type bin`.
  3. Agrega `distributionSha256Sum` en `gradle-wrapper.properties`. El valor es el sha256 del zip, calculado con `sha256sum`.
- [ ] **Step 2: `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
composeBom = "2024.12.01"
navigation = "2.8.5"
camerax = "1.4.1"
tflite = "2.16.1"
serialization = "1.7.3"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
camerax-video = { module = "androidx.camera:camera-video", version.ref = "camerax" }
tflite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tflite" }
tflite-gpu = { module = "org.tensorflow:tensorflow-lite-gpu", version.ref = "tflite" }
tflite-gpu-api = { module = "org.tensorflow:tensorflow-lite-gpu-api", version.ref = "tflite" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Scripts de build.**
  - **`settings.gradle.kts`:** `pluginManagement` y `dependencyResolutionManagement` usan `google()` y `mavenCentral()`. `rootProject.name = "cuentapepas"`. `include(":core", ":app")`.
  - **`build.gradle.kts` raíz:** los plugins con `apply false`.
  - **`gradle.properties`:** `org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`.
  - **`core/build.gradle.kts`:**

```kotlin
plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
tasks.test { testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
```

  - **`app/build.gradle.kts`:**
    - Plugins: android-application, kotlin-android, kotlin-compose y kotlin-serialization.
    - `namespace`/`applicationId` `app.cuentapepas`, `compileSdk 35`, `minSdk 26`, `targetSdk 35`, `versionCode 1`, `versionName "0.1.0"`.
    - `buildFeatures { compose = true; buildConfig = true }`, `androidResources { noCompress += "tflite" }`.
    - `compileOptions` en 17 y `kotlinOptions jvmTarget "17"`.
    - `packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }`.
    - Dependencias: `implementation(project(":core"))`, todas las librerías del catálogo y `debugImplementation("androidx.compose.ui:ui-tooling")`.
    - El `buildType` debug firma con la clave de depuración. Esa clave la crea AGP en `ANDROID_USER_HOME`, que apunta a `.tools/android-user-home`.
- [ ] **Step 4: Manifiesto.**
  - Permisos `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` y `WAKE_LOCK`, y `<uses-feature android:name="android.hardware.camera.any"/>`.
  - `MainActivity` con `android:screenOrientation="sensorLandscape"` y `android:configChanges="orientation|screenSize|keyboardHidden"`.
  - `FileProvider` con la autoridad `app.cuentapepas.fileprovider` y `res/xml/file_paths.xml`, que expone `files-path name="exports" path="exports/"`.
- [ ] **Step 5:** `MainActivity` mínimo (Compose `Text("Cuentapepas")`) y `SanityTest` con `assertEquals(4, 2 + 2)`.
- [ ] **Step 6: Verificar.** Corre `./gradlew --no-daemon :core:test :app:assembleDebug`; la salida esperada es `BUILD SUCCESSFUL` y el archivo `app/build/outputs/apk/debug/app-debug.apk`. Comprueba además que no aparezcan `C:/Users/*/.gradle` ni `C:/Users/*/.android`.
- [ ] **Step 7: Commit** `build(android): gradle skeleton with core (JVM) and app modules`.

---

### Task B2: Geometría y posprocesado YOLOX (paridad con Python)

**Files:** `core/.../geometry/Box.kt`, `detect/Detection.kt`, `detect/ModelSpec.kt`, `detect/Letterbox.kt`, `detect/YoloxPostprocessor.kt`. Pruebas: `BoxTest.kt`, `YoloxPostprocessorTest.kt`, `LetterboxTest.kt`, `ModelSpecTest.kt`.

**Contrato que debe cumplirse exactamente** (es el mismo `postprocess.py` del Plan A):
- `score = obj * max(cls)` y `cls = argmax`.
- Filtro `score >= threshold`.
- Orden por score descendente y estable: si hay empate, conserva el orden de índice.
- NMS voraz sin distinguir clase: suprime cuando `IoU > nmsIou`.
- IoU sin +1.
- Máximo `maxDetections`.
- Las cajas se forman como `x1=cx-w/2`, etc.

- [ ] **Step 1: Pruebas que fallan.**

```kotlin
// BoxTest.kt
class BoxTest {
    @Test fun iouOfIdenticalIsOne() = assertEquals(1f, Box(0f,0f,10f,10f).iou(Box(0f,0f,10f,10f)), 1e-6f)
    @Test fun iouHalfOverlap() = assertEquals(1f/3f, Box(0f,0f,10f,10f).iou(Box(5f,0f,15f,10f)), 1e-6f)
    @Test fun iouDisjointIsZero() = assertEquals(0f, Box(0f,0f,1f,1f).iou(Box(2f,2f,3f,3f)), 0f)
    @Test fun translate() = assertEquals(Box(1f,2f,11f,12f), Box(0f,0f,10f,10f).translate(1f, 2f))
}
// YoloxPostprocessorTest.kt
class YoloxPostprocessorTest {
    private fun raw(vararg rows: FloatArray) = rows.flatMap { it.toList() }.toFloatArray()
    private fun row(cx: Float, cy: Float, w: Float, h: Float, obj: Float, vararg cls: Float) = floatArrayOf(cx, cy, w, h, obj, *cls)
    private val pp = YoloxPostprocessor(numClasses = 5)

    @Test fun thresholdUsesObjTimesClsAndArgmax() {
        val out = raw(row(100f,100f,20f,20f,0.9f, 0.1f,0.1f,0.8f,0f,0f), row(300f,100f,20f,20f,0.3f, 0.9f,0f,0f,0f,0f))
        val d = pp.process(out, numAnchors = 2, transposed = false, scoreThreshold = 0.5f, nmsIou = 0.45f, maxDetections = 300)
        assertEquals(1, d.size); assertEquals(2, d[0].cls); assertEquals(0.72f, d[0].score, 1e-6f)
        assertEquals(Box(90f, 90f, 110f, 110f), d[0].box)
    }
    @Test fun nmsIsClassAgnostic() {
        val out = raw(row(100f,100f,20f,20f,0.9f, 0.9f,0f,0f,0f,0f), row(102f,100f,20f,20f,0.9f, 0f,0f,0.8f,0f,0f))
        val d = pp.process(out, 2, false, 0.1f, 0.45f, 300)
        assertEquals(1, d.size); assertEquals(0, d[0].cls)
    }
    @Test fun transposedLayoutGivesSameResult() {
        val a = row(100f,100f,20f,20f,0.9f, 0.1f,0.1f,0.8f,0f,0f); val b = row(300f,100f,20f,20f,0.9f, 0.9f,0f,0f,0f,0f)
        val normal = pp.process(raw(a, b), 2, false, 0.3f, 0.45f, 300)
        val t = FloatArray(20); for (i in 0 until 2) for (j in 0 until 10) t[j * 2 + i] = (if (i == 0) a else b)[j]
        assertEquals(normal, pp.process(t, 2, true, 0.3f, 0.45f, 300))
    }
    @Test fun classProbsKeptForMaturityVote() {
        val d = pp.process(raw(row(50f,50f,10f,10f,1f, 0.2f,0.6f,0.2f,0f,0f)), 1, false, 0.1f, 0.45f, 300)[0]
        assertArrayEquals(floatArrayOf(0.2f,0.6f,0.2f,0f,0f), d.classProbs, 1e-6f)
    }
    @Test fun maxDetectionsCaps() {
        val rows = (0 until 10).map { row(it * 50f, 10f, 10f, 10f, 1f, 1f,0f,0f,0f,0f) }.toTypedArray()
        assertEquals(3, pp.process(raw(*rows), 10, false, 0.1f, 0.45f, 3).size)
    }
    /** Parity with Python reference (fixtures from Plan A Task A7); skipped until fixtures exist. */
    @Test fun matchesPythonFixtures() {
        val dir = javaClass.getResource("/fixtures") ?: return assumeTrue("fixtures not generated yet", false)
        val folder = java.io.File(dir.toURI())
        val raws = folder.listFiles { f -> f.name.startsWith("raw_") }.orEmpty()
        assumeTrue(raws.isNotEmpty())
        for (rawFile in raws) {
            val k = rawFile.name.removePrefix("raw_").removeSuffix(".bin")
            val buf = java.nio.ByteBuffer.wrap(rawFile.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(buf.remaining()).also { buf.get(it) }
            val exp = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(folder, "expected_$k.json").readText()).jsonObject
            val dets = pp.process(arr, arr.size / 10, false, exp["score_threshold"]!!.jsonPrimitive.float, exp["nms_iou"]!!.jsonPrimitive.float, 300)
            val expected = exp["detections"]!!.jsonArray
            assertEquals("count for $k", expected.size, dets.size)
            expected.forEachIndexed { i, e ->
                val box = e.jsonObject["box"]!!.jsonArray.map { it.jsonPrimitive.float }
                assertEquals(e.jsonObject["cls"]!!.jsonPrimitive.int, dets[i].cls)
                assertEquals(box[0], dets[i].box.x1, 1e-3f); assertEquals(box[3], dets[i].box.y2, 1e-3f)
            }
        }
    }
}
// LetterboxTest.kt
class LetterboxTest {
    @Test fun frame1280x720Into640x384() {
        val lb = Letterbox.fit(srcWidth = 1280, srcHeight = 720, dstWidth = 640, dstHeight = 384)
        assertEquals(0.5f, lb.scale, 1e-6f); assertEquals(640, lb.resizedWidth); assertEquals(360, lb.resizedHeight)
        assertEquals(Box(200f, 100f, 400f, 300f), lb.toSource(Box(100f, 50f, 200f, 150f)))
    }
    @Test fun toSourceClipsToFrame() {
        val lb = Letterbox.fit(1280, 720, 640, 384)
        assertEquals(Box(0f, 600f, 1280f, 720f), lb.toSource(Box(-10f, 300f, 700f, 380f)))
    }
}
// ModelSpecTest.kt — parsea un model.json de ejemplo (el JSON del Plan A Task A6) y verifica
// inputWidth=640, inputHeight=384, classes.size=5, numAnchors=5040, transposed=false (default),
// defaultScoreThreshold=0.30f, fp16 file name.
```

- [ ] **Step 2: Implementar.**
  - **`Box`:** data class de Float con `width`, `height`, `area` (0 si es negativa), `cx`, `cy`, `iou`, `translate` y `clip(w, h)`.
  - **`Detection(box, score, cls, classProbs: FloatArray)`:** sobrescribe `equals` y `hashCode` usando `contentEquals` para `classProbs`.
  - **`Letterbox.fit`:**
    - Calcula `scale = min(dstH/srcH, dstW/srcW)`, `resizedWidth = (srcW*scale).roundToInt()` y la altura igual.
    - `toSource(b) = Box(b.x1/scale, …).clip(srcW, srcH)`.
    - `toModel(b)` es la operación inversa, para las pruebas.
  - **`ModelSpec`:** `@Serializable` con el esquema de `model.json`, y `ModelSpec.parse(json: String)` con `Json { ignoreUnknownKeys = true }`. Propiedades derivadas: `numAnchors = output.shape[1]` (o `[2]` si `transposed`) y `numClasses = classes.size`.
  - **`YoloxPostprocessor.process`:** sin asignaciones por anchor en el camino caliente. Primero un pase de filtro que guarda índices y scores en arreglos reutilizables; luego el orden con `sortedWith(compareByDescending { score }.thenBy { index })`; luego el NMS.
- [ ] **Step 3:** `./gradlew --no-daemon :core:test` → PASS. La prueba de fixtures se salta si no existen.
- [ ] **Step 4: Commit** `feat(core): YOLOX postprocess, letterbox and model.json spec (Python parity)`.

---

### Task B3: Tracker ByteTrack con compensación de movimiento de cámara

**Files:** `track/KalmanFilterXyah.kt`, `track/LinearAssignment.kt`, `track/STrack.kt`, `track/ByteTracker.kt`. Pruebas: `KalmanFilterXyahTest.kt`, `LinearAssignmentTest.kt`, `ByteTrackerTest.kt`.

**Especificación (fiel a ByteTrack, MIT, con traslación de cámara):**
- **Kalman `xyah`:** el estado es `[cx, cy, a=w/h, h, vcx, vcy, va, vh]`, con `stdWeightPosition = 1/20` y `stdWeightVelocity = 1/160`. `initiate`, `predict`, `project` y `update` son idénticos a `kalman_filter.py` de ByteTrack; la ganancia se calcula con la inversa de Cholesky de una matriz 4×4. Se usa `DoubleArray`, con matrices de 8×8 y 4×4 en funciones privadas.
- **`LinearAssignment.solve(cost: Array<DoubleArray>, threshold: Double): Assignment(matches, unmatchedRows, unmatchedCols)`:**
  1. Húngaro (Kuhn-Munkres) O(n³) sobre una matriz rellenada a cuadrada con valor `threshold + 1e-4`.
  2. Se descartan los pares con `cost > threshold`.
  3. Las matrices vacías devuelven todo sin asignar.
- **`ByteTracker(config: TrackerConfig)`.**
  - **Valores por defecto:** `highThresh=0.5`, `lowThresh=0.1`, `newTrackThresh=0.6`, `matchThresh=0.8`, `secondMatchThresh=0.5`, `unconfirmedMatchThresh=0.7`, `maxTimeLost=30` cuadros, `fuseScore=true`.
  - **`update(detections: List<Detection>, cameraShift: Shift = Shift.ZERO): List<TrackSnapshot>`**, donde `Shift(dx, dy)` está en píxeles del cuadro fuente y es el desplazamiento del contenido desde el cuadro anterior. Pasos:
    1. `frameId++`. Se separan las detecciones en `high` (`score >= highThresh`) y `low` (`lowThresh <= score < highThresh`).
    2. `unconfirmed` son los tracks Tracked y no activados. `tracked` son los Tracked y activados. `pool = tracked ∪ lost`.
    3. Se predice con Kalman cada track de `pool`. A los Lost se les pone velocidad de altura 0 antes de predecir, como en ByteTrack (`mean[7]=0`). Después **se aplica la cámara: `mean[0] += dx; mean[1] += dy`** a todos los de `pool` y también a `unconfirmed`, que se predicen igual.
    4. **Primera asociación** entre `pool` y `high`. El costo es `1 - IoU`; con `fuseScore`, el costo es `1 - IoU*score`. Umbral `matchThresh`. Los emparejados se actualizan si estaban Tracked o se reactivan si estaban Lost.
    5. **Segunda asociación** entre los tracks de `pool` que siguen sin pareja y en estado Tracked, y `low`, con costo `1 - IoU` y umbral `secondMatchThresh`. Los emparejados se actualizan; los tracks Tracked que siguen sin pareja pasan a Lost.
    6. **`unconfirmed` contra los `high` restantes**, con umbral `unconfirmedMatchThresh`. Los emparejados se actualizan y se activan; los que no se emparejan se eliminan.
    7. **Altas nuevas:** los `high` restantes con `score >= newTrackThresh` crean un track nuevo. Se activa al instante solo si `frameId == 1`; si no, queda como no confirmado. **El id se asigna al activarse** (contador monótono que empieza en 1).
    8. Se eliminan los Lost con `frameId - endFrame > maxTimeLost`.
    9. Se devuelven los `TrackSnapshot` de los tracks Tracked y activados.
  - **`STrack`** acumula `hits` (número de actualizaciones, contando la creación), `scoreSum`, `classProbSum` (cada `classProbs` multiplicado por el score) y `startFrame`/`endFrame`.
  - **`TrackSnapshot(id, box, hits, meanScore, classProbs (classProbSum normalizado a suma 1), frameId)`.**
- [ ] **Step 1: Pruebas que fallan.**
  - **Kalman:** con `initiate` en (100, 50, 0.5, 20), `predict` deja la media igual y la covarianza crece. Tras `update` con la misma medición, la media sigue igual y la covarianza diagonal disminuye. Con 10 actualizaciones de un objeto que avanza +5 px por cuadro en x, la velocidad estimada `mean[4]` queda en 5 ± 0,5.
  - **Asignación:**
    - La matriz `[[0.1,0.9],[0.9,0.1]]` empareja (0,0) y (1,1).
    - La matriz `[[0.1,0.2,0.9]]` da 1 pareja y 2 columnas sin asignar.
    - Con umbral 0.5, `[[0.6]]` no empareja nada.
    - Una matriz aleatoria de 6×6 da el costo mínimo, verificado por fuerza bruta sobre las permutaciones.
  - **Tracker:**
    1. `objectsMovingLinearlyKeepIds`: 5 cajas de 12×12 en una fila, separadas 30 px, que avanzan +4 px/cuadro en x durante 40 cuadros con score 0.9. Debe haber exactamente 5 ids distintos y cada id debe quedar estable desde el cuadro 2.
    2. `occlusionShorterThanBufferKeepsId`: 1 objeto con detección ausente en los cuadros 10 a 20 (11 cuadros < 30) que reaparece en su trayectoria. Debe conservar el mismo id.
    3. `occlusionLongerThanBufferGetsNewId`: ausente 40 cuadros, debe recibir un id nuevo.
    4. `cameraShiftCompensated`: 8 cajas de 10×10 que se desplazan 15 px/cuadro (más que su ancho, así que sin compensar el IoU con lo predicho sería 0 al inicio). Con `cameraShift=(15,0)` cada cuadro, debe haber 8 ids estables. Sin el shift, la prueba documenta que se crean más de 8 ids; esa aserción se deja como `assertTrue(idsWithout > 8)`.
    5. `lowScoreDetectionsExtendButDontCreate`: un track existente sigue con detecciones de score 0.3. Detecciones nuevas de 0.3 no crean tracks.
    6. `classProbsAccumulateWeighted`: dos actualizaciones, `(score 1, probs [1,0,0,0,0])` y `(score 0.5, probs [0,1,0,0,0])`, dan `classProbs ≈ [0.667, 0.333, 0, 0, 0]`.
- [ ] **Step 2: Implementar** hasta que todo pase.
- [ ] **Step 3: Rendimiento.** `ByteTrackerPerfTest`: 300 detecciones por cuadro durante 100 cuadros debe tardar menos de 3 s en la JVM (cota amplia, para detectar comportamiento O(n⁴)).
- [ ] **Step 4: Commit** `feat(core): ByteTrack tracker with Kalman, Hungarian assignment and camera-motion compensation`.

---

### Task B4: Contador con confirmación, voto de madurez y plantas

**Files:** `count/ClassCounts.kt`, `count/TrackCounter.kt`. Pruebas: `TrackCounterTest.kt`.

- **`ClassCounts(val perClass: IntArray)`:** con `total`, `plus`, `get(i)` y `ClassCounts.zero(n)`. Es `@Serializable`, con `equals` que usa `contentEquals`.
- **`TrackCounter(numClasses, minHits = 3, minMeanScore = 0.40f)`:**
  - `startPlant(plantNumber)`: fija la planta activa.
  - `observe(tracks: List<TrackSnapshot>)`. Para cada snapshot:
    - Si su id ya está contado, actualiza sus `classProbs`.
    - Si no, y cumple `hits >= minHits && meanScore >= minMeanScore`, lo cuenta **una sola vez** y lo asigna a la planta activa en ese momento.
  - `currentPlantCounts()`, `plantCounts(n)`, `sessionCounts()` y `plantNumbers()`. La clase de cada track contado es `argmax(classProbs)`, con empate al índice menor.
  - `countedTracks(): List<CountedTrack(id, plantNumber, cls, classProbs)>`.
  - Llamar `observe` antes de `startPlant` lanza `IllegalStateException`. No se cuenta en una "planta 0" implícita.
- [ ] **Step 1: Pruebas.**
  - Un track con `hits` 1, 2, 3 se cuenta en el tercer cuadro y nunca más, aunque siga visible 100 cuadros.
  - Un track con `meanScore` 0.3 no se cuenta.
  - Un track confirmado en la planta 1 sigue contando para la planta 1 aunque siga visible después de `startPlant(2)`.
  - Si las probabilidades de un track cambian de verde a maduro antes de terminar, la clase final en los conteos es maduro.
  - `sessionCounts() == plantCounts(1) + plantCounts(2)`.
- [ ] **Step 2: Implementar.** **Step 3:** PASS. **Step 4: Commit** `feat(core): track counter with confirmation, maturity vote and per-plant attribution`.

---

### Task B5: Calidad de imagen, movimiento global y avisos

**Files:** `quality/LumaImage.kt`, `quality/QualityMetrics.kt`, `quality/GlobalMotionEstimator.kt`, `quality/QualityAdvisor.kt`. Pruebas, una por archivo.

- **`LumaImage(width, height, data: ByteArray)`:** valores sin signo 0–255. `LumaImage.downscale(src: ByteBuffer, srcWidth, srcHeight, rowStride, pixelStride, dstWidth = 160, dstHeight = 90)` promedia cada bloque usando una submuestra de 2×2 por bloque, por costo. `get(x, y): Int`.
- **`QualityMetrics`:**
  - `sharpness(img)`: varianza del laplaciano de 4 vecinos sobre el interior.
  - `brightness(img) = Brightness(mean, darkFraction (<20), brightFraction (>235))`.
- **`GlobalMotionEstimator(searchRadius = 6, levels = 3)`:** `estimate(prev, curr): Shift`.
  - Usa una pirámide en que cada nivel promedia 2×2 del anterior.
  - Recorre los niveles de grueso a fino. En cada uno busca en ±`searchRadius` alrededor de `2 * estimado_anterior` minimizando el SAD medio sobre la región de solape, y exige un solape mínimo del 50 %.
  - Devuelve `Shift(dx, dy)` en píxeles de la imagen de 160×90, con la convención **contenido de `prev` en (x, y) aparece en `curr` en (x+dx, y+dy)**.
  - `Shift.scaled(sx, sy)` lo lleva a píxeles del cuadro fuente.
- **`QualityAdvisor(thresholds: QualityThresholds)`:** `update(s: QualitySample): Set<QualityWarning>`, con `QualitySample(sharpness, meanBrightness, brightFraction, frameWidthsPerSecond)`.
  - **Umbrales por defecto, provisionales:** `minSharpness=25.0`, `minBrightness=45.0`, `maxBrightness=215.0`, `maxBrightFraction=0.25`, `maxFrameWidthsPerSecond=1.0`, `onFrames=5`, `offFrames=10`.
  - **Avisos:** `TOO_FAST`, `BLURRY`, `TOO_DARK` y `BACKLIGHT`. `BACKLIGHT` salta si `mean > maxBrightness` o `brightFraction > maxBrightFraction`.
  - **Histéresis:** un aviso se activa tras `onFrames` cuadros seguidos en falta y se apaga tras `offFrames` cuadros seguidos bien.
- [ ] **Step 1: Pruebas.**
  - `downscale` con un plano de 1280×720 de valor constante 100 da 100 en todos los píxeles. Con `rowStride > width` (relleno) lee bien.
  - `sharpness`: un tablero de ajedrez da un valor mucho mayor que el mismo tablero suavizado con una caja de 5×5, y la imagen constante da 0.
  - `brightness`: una imagen que es mitad 0 y mitad 255 da `mean = 127.5`, `dark = 0.5` y `bright = 0.5`.
  - `GlobalMotionEstimator`: sobre una textura pseudoaleatoria con semilla fija de 200×120, se recorta una ventana de 160×90 en (20, 15) y otra en (20-dx, 15-dy).
    - Para (dx, dy) en {(0,0), (3,-2), (-7,5), (12,0), (-15,-9)}, el estimado debe ser exacto.
    - Sobre dos imágenes de ruido independientes no debe lanzar excepciones.
  - `QualityAdvisor`:
    - 4 cuadros borrosos no avisan y el 5.º sí.
    - Luego hacen falta 10 cuadros nítidos para apagar el aviso.
    - Varios avisos pueden estar activos a la vez.
- [ ] **Step 2: Implementar.** **Step 3:** PASS. **Step 4: Commit** `feat(core): frame quality metrics, global motion estimation and warning hysteresis`.

---

### Task B6: Estadística — bootstrap, calibración y estimación por lote

**Files:** `stats/Interval.kt`, `stats/Bootstrap.kt`, `calibration/RatioCalibrator.kt`, `estimate/LotEstimator.kt`. Pruebas: `BootstrapTest.kt`, `RatioCalibratorTest.kt`, `LotEstimatorTest.kt`.

- **`Interval(low, high)`** y **`Estimate(value, ci: Interval)`**, ambos `@Serializable`.
- **`Bootstrap.percentile(values: DoubleArray, confidence) : Interval`.** Ordena y toma los cuantiles `(1-c)/2` y `1-(1-c)/2` con interpolación lineal, igual a `numpy.quantile` con el método por defecto. El generador es `java.util.SplittableRandom(seed)`, así que el resultado es determinista.
- **`RatioCalibrator.fit(samples: List<CalibrationSample>, minSamples = 10, confidence = 0.90, replicates = 2000, seed = 20261011L): CalibrationResult`.**
  - `CalibrationSample(plantId, visible, manual)`.
  - El resultado es `CalibrationResult(n, k, kCi, plantFactorInterval, loocvMeanAbsPctError, usable, reason)`.
  - **Cálculo:**
    - `k = Σmanual/Σvisible`.
    - Bootstrap: remuestrea las muestras con reemplazo y obtiene `k*_b`. `kCi` es su intervalo percentil.
    - `plantFactorInterval`: percentiles de `k*_b · e_b`, donde `e_b = r_j/k` para una muestra `j` tomada al azar con `visible > 0`.
    - LOOCV: media de `|k_{-i}·v_i − m_i| / m_i` sobre `m_i > 0`.
  - **Cuándo no es usable** (`usable = false`, con la razón en español): n < minSamples ("Se necesitan al menos 10 plantas de muestra"), `Σvisible == 0`, o alguna muestra con `manual < 0` o `visible < 0`, que además lanza `IllegalArgumentException`.
- **`LotEstimator.estimate(input: LotInput, minPlants = 10, confidence = 0.90, replicates = 2000, seed = 20261011L): LotEstimate`.**
  - `LotInput(totalPlants: Int?, plants: List<PlantVisible>, calibrationSamples: List<CalibrationSample>, cherriesPerKg: Double?)`.
  - `PlantVisible(plantId, sides: List<ClassCounts>)`, con 1 o 2 lados.
  - **Visible de una planta:** la media de los totales de sus lados.
  - **Proporción de madurez:** `Σ_plantas mean_lados(perClass) / Σ visible`.
  - **Estados:**
    - `INSUFFICIENT_EVIDENCE` si `plants.size < minPlants`. Todos los campos numéricos quedan en null y la nota dice "Evidencia insuficiente: N de 10 plantas mínimas".
    - `VISIBLE_ONLY` si la calibración no es usable. Se llenan `visiblePerPlant` (media e IC bootstrap sobre plantas) y `maturityShare` (IC bootstrap). `totalCherries` y `totalKg` quedan en null, con la nota "Sin calibración: se reportan pepas visibles, no cosecha".
    - `CALIBRATED`. En cada réplica se remuestrean a la vez las plantas y las muestras de calibración: `total*_b = N · k*_b · meanVisible*_b`. Si `totalPlants` es null, `totalCherries` queda en null y se añade la nota "Falta el número total de plantas del lote". `totalKg = totalCherries / cherriesPerKg` solo si se dio `cherriesPerKg > 0`.
  - `coverage = plants.size / totalPlants` cuando `totalPlants != null`. Si no, queda en null.
  - **Siempre se agrega la nota** "Supone que las plantas recorridas representan el lote; lo no recorrido no se interpola".
- [ ] **Step 1: Pruebas.**
  - `percentile` de 1..100 al 90 % da (5.95, 95.05).
  - **Calibración sintética:** 30 plantas con `visible ~ U(50, 300)` y `manual = round(3.0 · visible · ε)`, con ε log-normal (σ = 0.15), semilla fija:
    - `k` en [2.8, 3.2] y `kCi` contiene 3.0;
    - el ancho de `plantFactorInterval` es mayor que el de `kCi`;
    - el LOOCV está entre 0.05 y 0.25;
    - con 9 muestras, `usable = false`.
  - **Determinismo:** la misma semilla da el mismo resultado.
  - **Cobertura del IC** (prueba lenta, con `@Category` no hace falta; mantenerla por debajo de 5 s): en 200 simulaciones con 15 muestras, la fracción de `kCi` que contiene el `k` real está en [0.80, 0.97].
  - **LotEstimator:**
    - 5 plantas dan `INSUFFICIENT_EVIDENCE`.
    - 12 plantas sin calibración dan `VISIBLE_ONLY`, con `totalCherries == null` y `visiblePerPlant.value` igual a la media exacta.
    - Con calibración usable de `k ≈ 3` y N = 1000, el total queda cerca de `3000 · media` y su IC lo contiene.
    - Si una planta tiene 2 lados, se usa el promedio de los lados.
    - Las proporciones de madurez suman 1 ± 1e-9.
    - `cherriesPerKg = null` deja `totalKg == null`.
- [ ] **Step 2: Implementar.** **Step 3:** PASS. **Step 4: Commit** `feat(core): bootstrap ratio calibration and lot estimate with intervals and coverage`.

---

### Task B7: Sesiones, recorridos repetidos, GPS y estadísticas de rendimiento

**Files:** `session/Models.kt`, `session/PassResolver.kt`, `session/Geo.kt`, `perf/PerfStats.kt`. Pruebas: `PassResolverTest.kt`, `GeoTest.kt`, `PerfStatsTest.kt`, `ModelsSerializationTest.kt`.

- **`Models.kt`** (todos `@Serializable`):
  - `Lot(id, name, totalPlants: Int?, cherriesPerKg: Double?, createdAtMs)`.
  - `enum Side { A, B }` y `enum SessionStatus { ACTIVE, COMPLETED, REPLACED }`.
  - `SessionMeta(id, lotId, row: Int, side: Side, firstPlant: Int, plantStep: Int, isCalibrationSample: Boolean, evaluationMode: Boolean, startedAtMs, endedAtMs: Long?, status, appVersion, modelId, settings: Map<String, String>)`.
  - `PlantRecord(sessionId, plantNumber, counts: ClassCounts, startedAtMs, endedAtMs: Long?, manualCount: Int? = null)`.
  - `GpsPoint(tMs, lat, lon, accuracyM: Float)`.
  - `SessionStats(framesProcessed, durationS, fpsMedian, fpsP10, inferenceMsMedian, inferenceMsP90, delegate: String, maxThermalStatus: Int, batteryStartPct: Int?, batteryEndPct: Int?, batteryPctPerHour: Double?, warningFraction: Map<String, Double>, gpsAccuracyMedianM: Double?, gpsAccuracyP90M: Double?)`.
- **`PassResolver`:**
  - `conflictingSession(sessions, lotId, row, side): SessionMeta?` devuelve la sesión COMPLETED con la misma clave.
  - `effectivePlants(sessions, plants): Map<PlantKey(lotId, row, plantNumber), List<PlantRecord>>`. Para cada (lote, surco, lado, planta) toma el registro de la sesión COMPLETED más reciente (por `startedAtMs`) e ignora las REPLACED. Después agrupa los lados por `PlantKey`, con un máximo de 2.
  - `calibrationSamples(sessions, plants): List<CalibrationSample>` toma los registros efectivos de sesiones con `isCalibrationSample` y `manualCount != null`. `visible` es `counts.total`. Si la planta tiene 2 lados, `visible` es el promedio redondeado. El id es `"$lotId/$row/$plantNumber"`.
- **`Geo`:**
  - `haversineM(a, b)`.
  - `goodPoints(points, maxAccuracyM = 10f)`.
  - `overlapFraction(newTrack, oldTrack, radiusM = 3.0)`: fracción de los puntos buenos del recorrido nuevo que quedan a `radiusM` o menos de algún punto bueno del anterior. Devuelve 0.0 si no hay puntos buenos.
  - `possibleRepeats(newTrack, others: Map<sessionId, List<GpsPoint>>, threshold = 0.7): List<String>`.
- **`PerfStats`:** acumulador de latencias e intervalos entre cuadros. `median`, `p10` y `p90` se calculan con `percentileOf` (orden y cuantil lineal). FPS = 1000 / intervalo en ms; el p10 de FPS sale del p90 del intervalo.
- [ ] **Step 1: Pruebas.**
  - Dos sesiones COMPLETED del mismo lote/surco/lado: los registros efectivos son los de la más reciente y **nunca se suman**.
  - Una sesión REPLACED se ignora.
  - Los lados A y B de la misma planta se agrupan en una sola `PlantKey` con 2 registros.
  - `calibrationSamples` promedia los lados.
  - `haversine` entre (4.0, -75.0) y (4.0, -75.001) da ≈ 111 m ± 1.
  - `overlapFraction` de un trazado contra sí mismo da 1.0. Contra una copia desplazada 20 m da 0.0. Con puntos de precisión 30 m da 0.0 (se filtran).
  - `PerfStats` con intervalos de 66.6 ms da `fpsMedian ≈ 15`.
  - El viaje JSON de ida y vuelta conserva igualdad para `SessionMeta` y `PlantRecord`.
- [ ] **Step 2: Implementar.** **Step 3:** PASS. **Step 4: Commit** `feat(core): session models, repeated-pass resolution, GPS overlap and perf stats`.

---

### Task B8: Almacenamiento, ajustes y exportación (app)

**Files:** `data/SessionStore.kt`, `data/SettingsStore.kt`, `data/Exporter.kt`, `CuentapepasApp.kt`.

- **`SessionStore(root: File = context.filesDir)`.**
  - Archivos:
    - `lots.json`, que guarda `List<Lot>`;
    - `sessions/<id>/meta.json`, `plants.json`, `gps.jsonl` (solo agregar), `stats.json` y `frames.jsonl` (modo evaluación);
    - el video va en `getExternalFilesDir(DIRECTORY_MOVIES)/<id>.mp4`.
  - **Escritura atómica:** se escribe en `*.tmp` y luego `renameTo`. Si falla, **lanza** `IOException`; nunca la traga.
  - API con funciones `suspend` en `Dispatchers.IO`: `lots()`, `saveLot()`, `deleteLot()` (solo si no tiene sesiones), `sessions(lotId?)`, `createSession(meta)`, `updateSession(meta)`, `plants(sessionId)`, `savePlants(sessionId, list)`, `appendGps(sessionId, points)`, `gps(sessionId)`, `saveStats`, `stats`, `setManualCount(sessionId, plantNumber, count)` y `markReplaced(sessionId)`.
  - Un JSON corrupto lanza `CorruptDataException(path)` y la interfaz la muestra. No se recrean archivos vacíos para ocultar el problema.
- **`SettingsStore`:** guarda en SharedPreferences `QualityThresholds`, `TrackerConfig`, los parámetros del contador (`minHits`, `minMeanScore`), `scoreThreshold`, `nmsIou` y `gpsMaxAccuracyM`. Ofrece `snapshot(): Map<String, String>` para guardarlo en cada sesión.
- **`Exporter.exportSession(sessionId): File`.** Crea `filesDir/exports/<lote>_s<surco><lado>_<fecha>.zip` con meta, plants, gps, stats, `frames.jsonl` si existe y `plants.csv` (columnas `plant,green,semi_ripe,ripe,overripe,dry,total,manual`). El video no se incluye porque es grande; se comparte aparte. `shareIntent(file)` usa el FileProvider.
- **`CuentapepasApp`:** un `ServiceLocator` perezoso con `store`, `settings`, `modelSpec` (leído de `assets/model/model.json`) y `detectorFactory`.
- [ ] **Step 1:** Implementar. Una prueba JVM no aplica porque los archivos dependen de `Context`. La lógica de CSV va en una función pura `plantsCsv(plants: List<PlantRecord>): String` en `:core`, archivo `session/Csv.kt`, con la prueba `CsvTest`: encabezado y una fila.
- [ ] **Step 2:** `./gradlew --no-daemon :core:test :app:assembleDebug` → OK. **Commit** `feat(app): JSON session store, settings and zip export`.

---

### Task B9: Inferencia TFLite y canal de procesamiento por cuadro

**Files:** `inference/TfliteDetector.kt`, `inference/FramePreprocessor.kt`, `pipeline/FrameProcessor.kt`, `pipeline/EvaluationLogger.kt`, `device/DeviceMonitor.kt`.

- **`TfliteDetector(context, spec: ModelSpec, preferGpu = true)`.**
  - Carga `assets/model/<files.fp16.name>` con `FileUtil.loadMappedFile`. Si no existe, carga el fp32. Si tampoco existe, lanza `ModelMissingException` y la interfaz muestra "Falta el modelo: siga docs/…".
  - **Delegado:** si `CompatibilityList().isDelegateSupportedOnThisDevice`, usa `GpuDelegate(compat.bestOptionsForThisDevice)`. Si no, CPU con 4 hilos y XNNPACK. Si la creación con GPU lanza error, reintenta en CPU y **registra el motivo**.
  - `val delegateName` vale `"GPU"` o `"CPU"`.
  - `run(input: ByteBuffer): FloatArray` reutiliza los buffers. Se verifica que la forma de salida del intérprete coincide con `spec.output.shape`; si no, lanza `IllegalStateException` con las dos formas.
  - `close()`.
- **`FramePreprocessor(spec)`:** `prepare(image: ImageProxy): Prepared(input: ByteBuffer, letterbox: Letterbox, luma: LumaImage)`.
  - Usa `image.toBitmap()`. La rotación se ignora porque la actividad está en horizontal y `targetRotation` coincide; si `imageInfo.rotationDegrees != 0`, se rota con `Matrix`.
  - Escala con `createScaledBitmap(…, filter = true)` a `letterbox.resizedWidth × resizedHeight`. Lee los píxeles con `getPixels` en un `IntArray` reutilizado y escribe B, G, R como float en NHWC. Las filas de relleno se llenan con 114.
  - La luma sale de `image.planes[0]` con `LumaImage.downscale`.
- **`FrameProcessor(detector, preprocessor, settings, counter, tracker, advisor, motion, perf, logger?)`.** Corre en un `Executor` de un solo hilo; es el analizador de CameraX.
  - `analyze(image)` hace, en orden:
    1. `prepare`.
    2. Movimiento: `motion.estimate(prevLuma, luma)` y luego `shift.scaled(srcW/160, srcH/90)`.
    3. Calidad.
    4. `detector.run`.
    5. `postprocess`, y `letterbox.toSource` sobre cada caja.
    6. `tracker.update(dets, shift)`.
    7. `counter.observe`, solo si la sesión no está en pausa.
    8. `advisor.update`.
    9. `perf`.
    10. Publica `FrameResult(frameIndex, tsNs, tracks, currentPlantCounts, sessionCounts, warnings, inferenceMs, fps, delegate)` en un `StateFlow`.
    11. Cierra `image`.
  - **Muestra de velocidad:** `frameWidthsPerSecond = |dx_src| / srcW / dtSeconds`.
  - **Térmico:** si `DeviceMonitor.thermalStatus >= THERMAL_STATUS_SEVERE`, procesa uno de cada dos cuadros y lo marca en `FrameResult` (`throttled = true`). La interfaz lo muestra.
- **`EvaluationLogger(file)`:** escribe JSONL con un `BufferedWriter` en un hilo propio. Cada línea es `{f, ts, plant, q:{sharp, bright, brightFrac, dx, dy}, inf_ms, dets:[[x1,y1,x2,y2,score,cls],...], tracks:[[id,x1,y1,x2,y2,hits,cls],...], warn:[...]}`. Los errores de escritura se propagan al `FrameResult` (`loggingError`) y la interfaz los muestra; no se ignoran.
- **`DeviceMonitor(context)`:** `thermalStatus` (API 29+, `PowerManager.addThermalStatusListener`; en versiones anteriores vale -1, que se muestra como "sin dato"), `batteryPct()`, `batteryTempC()`, `maxThermalSeen`.
- [ ] **Step 1:** Implementar y compilar (`:app:assembleDebug`).
- [ ] **Step 2:** Prueba JVM en `:core` para la parte pura, que es la escritura NHWC en BGR con relleno. Se mueve a `core/detect/InputPacker.kt` con `pack(argb: IntArray, w, h, dstW, dstH, out: FloatArray)` y su prueba `InputPackerTest`: un píxel rojo `0xFFFF0000` produce B=0, G=0, R=255, y las filas de relleno valen 114. `FramePreprocessor` llama a `InputPacker`.
- [ ] **Step 3: Commit** `feat(app): TFLite detector (GPU/CPU), preprocessing and per-frame pipeline`.

---

### Task B10: Cámara y pantalla en vivo

**Files:** `camera/CameraController.kt`, `ui/live/LiveScreen.kt`, `ui/live/LiveViewModel.kt`, `ui/live/DetectionOverlay.kt`, `device/LocationRecorder.kt`. Modify: `MainActivity.kt` (tecla de volumen).

- **`CameraController`.**
  - Hace bind de `Preview` + `ImageAnalysis` (`ResolutionSelector` con preferencia 1280×720, relación 16:9, `STRATEGY_KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_YUV_420_888`) y, si `evaluationMode`, también `VideoCapture<Recorder>` (Quality.HD, sin audio).
  - Usa la cámara trasera.
  - Si el bind con 3 casos de uso falla, reintenta sin video y **avisa** con "El video de evaluación no está disponible en este equipo".
- **`LocationRecorder`.** `LocationManager.GPS_PROVIDER` cada 1000 ms y 0 m. Guarda puntos con `accuracy` en memoria y los persiste cada 10 s con `store.appendGps`. Expone `lastAccuracyM`. Sin permiso, la sesión sigue y el resumen dice "GPS: sin permiso".
- **`LiveViewModel`:**
  - Crea la sesión (`createSession`). Si existe una sesión en conflicto, primero `markReplaced`; el diálogo se resuelve en NewSession.
  - Arranca `counter.startPlant(firstPlant)`.
  - `nextPlant()`: guarda el `PlantRecord` de la planta actual con sus conteos y `endedAt`, y luego `startPlant(actual + plantStep)`.
  - `pause()` y `resume()`.
  - `finish()`: guarda la última planta, `SessionStats` (de `perf`, `DeviceMonitor`, el GPS y las fracciones de aviso), `endedAt` y `status = COMPLETED`, detiene la grabación y navega al resumen.
  - Si la app pasa a segundo plano con la sesión activa, la sesión queda en pausa y se guarda el progreso.
- **`LiveScreen`** (horizontal, con `FLAG_KEEP_SCREEN_ON`):
  - `PreviewView` en `AndroidView` con `scaleType = FILL_CENTER`.
  - `DetectionOverlay` en un Canvas. Mapea el cuadro fuente de 1280×720 a la vista con `scale = max(vw/1280, vh/720)` y desplazamiento centrado, igual que FILL_CENTER. Dibuja el rectángulo de cada track con el color de su clase (verde `#2E7D32`, pintón `#F9A825`, maduro `#C62828`, sobremaduro `#6A1B9A`, seco `#4E342E`), con trazo más grueso si ya está contado.
  - **Panel derecho:**
    - "Planta N" con el conteo de la planta por clase y su total;
    - "Recorrido" con el total por clase;
    - la etiqueta fija **"Pepas visibles"**.
  - **Barra superior:** FPS, delegado, estado térmico (Normal / Leve / Moderado / Severo / Crítico) con color, batería en % y precisión del GPS en m.
  - **Banner de avisos:** "Va muy rápido", "Imagen borrosa", "Poca luz" y "Contraluz", en rojo o ámbar.
  - **Botones:** "Siguiente planta" (grande, de al menos 96 dp de alto), "Pausa/Continuar" y "Terminar" (pide confirmación).
  - `MainActivity.onKeyDown`: `KEYCODE_VOLUME_DOWN` o `KEYCODE_VOLUME_UP` llama a `nextPlant()` cuando la pantalla en vivo está activa.
  - Los permisos de cámara y ubicación se piden con `rememberLauncherForActivityResult`. Sin cámara se muestra una explicación y no hay pantalla negra.
- [ ] **Step 1:** Implementar y compilar. **Step 2: Commit** `feat(app): live camera screen with overlay, counts, warnings and device status`.

---

### Task B11: Pantallas de inicio, nuevo recorrido, resumen, calibración, estimación, ajustes y acerca de

**Files:** los archivos `ui/*` restantes, más `ui/nav/AppNav.kt` y `ui/theme/Theme.kt`.

- **Navegación:** `home` → `lot/{lotId}` (lote con sus sesiones) → `new/{lotId}` → `live/{sessionId}` → `summary/{sessionId}`, más `calibration/{lotId}`, `estimate/{lotId}`, `bench`, `settings` y `about`.
- **Home:** lista de lotes con el número de recorridos y el estado de la calibración ("Calibrado (n=12)" o "Sin calibrar (n=4/10)"). Botón "Nuevo lote" con un diálogo para nombre, plantas totales (opcional) y pepas por kg (opcional, con la ayuda "Medir en su finca; no se usa valor por defecto"). Accesos a Benchmark, Ajustes y Acerca de.
- **NewSession:**
  - Campos: surco (número), lado A/B, primera planta (por defecto 1), dirección +1/−1, "Plantas de muestra para calibración" y "Modo evaluación (graba video y registro por cuadro)".
  - Si `PassResolver.conflictingSession` existe, aparece un diálogo: "Este surco y lado ya se recorrió el <fecha>. ¿Reemplazar ese recorrido? (los conteos no se suman)". Las opciones son Reemplazar y Cancelar.
  - **Aviso de GPS:** al terminar, `possibleRepeats` se calcula contra los otros recorridos del lote. Si el resultado no está vacío, el resumen muestra "El trazado GPS coincide ≥70 % con el recorrido X. ¿Es un surco repetido?" con dos acciones: "Marcar este como reemplazo de X" y "Son distintos".
- **Summary:**
  - Tabla de plantas por clase y total.
  - Si el recorrido es de calibración, cada planta tiene un campo "Conteo manual" que se guarda con `setManualCount`.
  - Estadísticas: duración, FPS mediana/p10, inferencia en ms, delegado, térmico máximo, % de batería por hora, % de cuadros con cada aviso y precisión del GPS (mediana/p90).
  - Botones "Exportar (zip)" y "Compartir video" (si hay modo evaluación).
- **Calibración del lote:**
  - Usa `RatioCalibrator.fit` sobre `PassResolver.calibrationSamples` del lote.
  - Muestra n, k, IC al 90 %, intervalo por planta y error LOOCV.
  - Si no es usable, muestra la razón. Si n < 10, se ve en grande "Sin calibración".
  - Explicación: "El factor convierte pepas visibles desde un lado en pepas totales de la planta. Solo vale para el mismo protocolo (un lado, distancia ~50–80 cm)".
- **Estimación del lote:**
  - Usa `LotEstimator.estimate` con `effectivePlants` y las muestras de calibración.
  - Muestra el estado en texto grande: Evidencia insuficiente, Solo pepas visibles o Calibrado.
  - Todas las cifras van como `valor (IC 90 %: bajo–alto)`.
  - La cobertura va como "X de N plantas (Y %)". Abajo se listan los surcos y lados recorridos. Si falta el total de plantas, dice "Cobertura: sin dato".
  - La proporción de madurez va como barras, cada una con su IC.
  - Las notas del estimador se muestran literalmente.
- **Ajustes:** campos numéricos de `SettingsStore` con la nota "Valores provisionales; se recalibran con datos de campo" y el botón "Restaurar valores".
- **Acerca de:** versión, `model_id`, métricas de `model.json`, limitaciones del modelo (texto de la ficha) y licencias/atribuciones (contenido de `NOTICE`, copiado a `assets/NOTICE.txt` por una tarea de Gradle `copyNotice` antes de `preBuild`).
- [ ] **Step 1:** Implementar y compilar. **Step 2: Commit** `feat(app): home, new session, summary, calibration, lot estimate, settings and about screens`.

---

### Task B12: Benchmark en el dispositivo

**Files:** `ui/bench/BenchmarkScreen.kt`, `ui/bench/BenchmarkRunner.kt`.

- **Opciones:** duración (1, 5, 15 min; por defecto 15) y delegado (Auto, CPU).
- **`BenchmarkRunner`:**
  - Carga las imágenes de `assets/bench/` (si no hay, usa un cuadro sintético con ruido e **indica** "sin imágenes reales").
  - Las prepara una sola vez y corre `detector.run` y `postprocess` en ciclo durante la duración elegida.
  - Cada 10 s registra `{t, inf_ms_median_last10s, thermal, battery_pct, battery_temp_c}`.
  - Al final calcula `PerfStats`, la tendencia (la latencia mediana del primer minuto contra la del último) y el % de batería por hora.
  - Guarda `filesDir/bench/<timestamp>.json`, que se puede compartir.
- **Pantalla:** gráfico simple de latencia contra tiempo dibujado en Canvas, resultados y "Compartir".
- [ ] **Step 1:** Implementar y compilar. **Step 2: Commit** `feat(app): sustained on-device benchmark (latency, thermal, battery)`.

---

### Task B13: Documentación, APK y verificación final

- [ ] **Step 1: `README.md`** (español): qué es, estado (v0.1 en prueba de campo), arquitectura, cómo preparar el entorno (`tools/setup-toolchain.sh`, `tools/env.ps1`), cómo entrenar y exportar (Plan A), cómo compilar el APK, licencias y la política de honestidad.
- [ ] **Step 2: `docs/guia-de-campo.md`** (español, para el responsable):
  - instalar el APK en el S22+ (activar "Instalar apps desconocidas", copiar `app-debug.apk` por USB o Drive);
  - protocolo de recorrido: horizontal, 50–80 cm, caminar ~0,5 m/s, "Siguiente planta" con la tecla de volumen;
  - cómo hacer las plantas de calibración (conteo manual completo), modo evaluación, benchmark de 15 min y qué exportar y enviar para evaluar;
  - qué significan los avisos y las cifras.
- [ ] **Step 3: ADRs `docs/adr/0001` a `0008`** según la §3 de la especificación, en el formato de almendra.
- [ ] **Step 4: Verificación.**
  1. `./gradlew --no-daemon :core:test :app:lintDebug :app:assembleDebug` y `uv run --no-sync pytest -q`; se pega el resumen.
  2. Se revisa que el APK contenga `assets/model/*.tflite`: `unzip -l app-debug.apk | grep tflite`.
  3. Se reporta el tamaño del APK.
- [ ] **Step 5:** Copiar el APK a `dist/cuentapepas-0.1.0-debug.apk` (`dist/` está en `.gitignore`). **Commit** `docs: README, field guide and ADRs`.
