# Cuentapepas

Cuentapepas cuenta pepas de café en video. Un celular recorre el surco caminando y la app detecta, sigue y clasifica por madurez cada pepa visible, para estimar la cosecha por planta y por lote. Todo corre sin conexión en un Android (Samsung Galaxy S22+ como dispositivo de referencia).

## Estado

- **Versión:** v0.1, en prueba de campo.
- **Datos de entrenamiento:** el modelo se entrena solo con datos de Uganda (Croppie coffee UG, CC BY-SA 4.0).
- **Datos colombianos:** todavía no hay. El modelo no se ha evaluado en fincas colombianas.
- **Cifras:** la app muestra "pepas visibles" hasta que exista una calibración con conteo manual. Ver [Principios de honestidad](#principios-de-honestidad).

## Principios de honestidad

Estos principios vienen de la especificación (§2) y de [ADR-0008](docs/adr/0008-politica-de-evidencia.md):

1. **Sin calibración no hay cosecha.** Mientras no exista un factor calibrado con su intervalo, toda cifra es "pepas visibles", no pepas reales ni kilos.
2. **Siempre con intervalo.** Ningún conteo, factor ni cosecha se reporta como un número suelto.
3. **No se interpola.** Lo que no se recorrió o no se muestreó queda como "sin dato". Ausencia de dato no es cero.
4. **Mínimos de evidencia.** Con menos de 10 plantas recorridas o de muestra, la app muestra "evidencia insuficiente" en lugar de una estimación.
5. **Cobertura visible.** Cada estimación de lote muestra cuántas plantas, surcos y lados se recorrieron.

## Arquitectura

```
ml/                 pipeline Python (uv, Python 3.12): datos, entrenamiento, exportación, evaluación
android/            app Android (Kotlin, Jetpack Compose, CameraX, LiteRT/TFLite)
  :core             Kotlin/JVM puro, sin Android: geometría, detección (posproceso YOLOX),
                    tracker, conteo, calidad, calibración, estimación de lote, sesiones
  :app              cámara, intérprete TFLite, GPS, temperatura y batería, sesiones en JSON,
                    exportación e interfaz
data/               taxonomía y catálogo de fuentes (los datos locales van en data/local/, fuera de git)
docs/               especificación, planes, ADRs, guía de campo y ficha de datos
tools/              scripts de entorno, instalación y entrenamiento
```

El detector va detrás de una interfaz para poder cambiarlo ([ADR-0004](docs/adr/0004-detector-yolox.md)). El tracker es propio y está escrito para portarse de Python a Kotlin ([ADR-0005](docs/adr/0005-tracker-propio-estilo-bytetrack.md)).

## Entorno aislado

Todo el entorno vive dentro de la carpeta del proyecto, en `.tools/`: JDK 17, SDK de Android, Gradle, el entorno de Python, cachés, datos temporales y secretos. Nada se instala en C:.

1. Instalar las herramientas (JDK 17 y SDK de Android) dentro de `.tools/`:

   ```bash
   bash tools/setup-toolchain.sh
   ```

2. Cargar el entorno antes de cualquier comando de compilación, descarga o entrenamiento:

   - Git Bash: `source tools/env.sh`
   - PowerShell: `. .\tools\env.ps1`

Las claves de API (por ejemplo, la de Roboflow) van en `.tools/secrets.env`, que está excluido de git. Lo carga automáticamente `env.sh` o `env.ps1`.

## Flujo de aprendizaje automático

Desde `ml/`, con el entorno cargado. Los comandos usan `uv run --no-sync`, como en `tools/train-detached.ps1`.

| Comando | Qué hace |
|---|---|
| `cuentapepas build-dataset --source <nombre> --raw <dir> --out <dir> --manifest <jsonl>` | Convierte una fuente a COCO, con división por grupo (foto o sesión de captura) y manifiesto con sha256. |
| `cuentapepas train [--epochs N] [--batch N] [--workers N] [--smoke]` | Entrena YOLOX-Nano (fp32) y guarda la corrida en `runs/<UTC>-<exp>/`. `--smoke` hace una prueba de 1 época con un subconjunto. |
| `cuentapepas eval --run runs/<id> [--split val]` | Calcula mAP COCO, recall por tamaño y matriz de confusión; escribe `metrics.json` en la corrida. |
| `cuentapepas export --run runs/<id>` | Exporta a ONNX y TFLite (fp16 y fp32), mide la diferencia de salidas frente a PyTorch (verificación de paridad) y escribe `model.json`. Por defecto escribe en `android/app/src/main/assets/model`. |
| `cuentapepas fixtures --model <fp32.tflite> --out <dir>` | Genera fixtures de paridad para los tests de Kotlin. |
| `cuentapepas bench-images --out <dir> [--n 20]` | Genera imágenes de prueba para el benchmark de la app, con `ATTRIBUTION.txt`. |

Antes del primer entrenamiento hay que preparar YOLOX y el checkpoint COCO:

```bash
bash tools/setup-yolox.sh      # clona YOLOX en la versión fijada y aplica los parches de compatibilidad
bash tools/get-pretrained.sh   # descarga yolox_nano_coco.pth y verifica su sha256
```

### Entrenamiento desacoplado

Un entrenamiento largo no debe depender de la terminal ni de la sesión. `tools/train-detached.ps1` corre `cuentapepas train` en segundo plano, pide a Windows que no suspenda el equipo mientras corre y escribe su registro en `.tools/train-main.log`. Se lanza con `Win32_Process` como indica el encabezado del script:

```powershell
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "<repo>\tools\train-detached.ps1"' }
```

Los parámetros son `-Epochs`, `-Batch` y `-Workers`.

Las pruebas del pipeline se corren con `uv run --no-sync pytest -q` desde `ml/`.

## Compilar el APK

Con el entorno cargado (`source tools/env.sh` o `. .\tools\env.ps1`):

```bash
cd android && ./gradlew --no-daemon :app:assembleDebug
```

El APK de depuración queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Las pruebas unitarias de `:core` corren en el PC, sin teléfono.

## Licencias

- **Código:** MIT (ver [LICENSE](LICENSE)).
- **Pesos del modelo entrenado con Croppie:** CC BY-SA 4.0. Son material adaptado de ese dataset y se distribuyen aparte del código, con la atribución y la declaración de modificaciones de [NOTICE](NOTICE). Ver [ADR-0002](docs/adr/0002-politica-de-licencias.md).
- **Otras dependencias:** YOLOX (Apache-2.0), TensorFlow Lite (Apache-2.0) y los pesos COCO de YOLOX, con atribución en [NOTICE](NOTICE).
- **Datos:** el proyecto no redistribuye datasets de terceros. Solo guarda scripts para descargarlos y manifiestos con hashes. Los datos locales están en `data/local/`, fuera de git.

La política de licencias prohíbe en lo distribuido YOLOv8/YOLOv11 de Ultralytics (AGPL-3.0), YOLOv9 oficial (GPL-3.0), BoxMOT (AGPL-3.0) y datos sin licencia.

## Documentación

- **Especificación de la Fase 0:** [docs/superpowers/specs/2026-10-11-fase0-factibilidad-conteo-design.md](docs/superpowers/specs/2026-10-11-fase0-factibilidad-conteo-design.md). Incluye principios, decisiones, taxonomía, datos, evaluación y el alcance de la app v1 (§14).
- **Plan del modelo de ML:** [docs/superpowers/plans/2026-10-11-plan-a-modelo-ml.md](docs/superpowers/plans/2026-10-11-plan-a-modelo-ml.md).
- **Decisiones (ADR):** [docs/adr/](docs/adr/README.md).
- **Guía de campo** para el responsable de la prueba: [docs/guia-de-campo.md](docs/guia-de-campo.md).
- **Ficha de datos de Croppie (Uganda):** [docs/datos/fichas/croppie_coffee_ug.md](docs/datos/fichas/croppie_coffee_ug.md).
