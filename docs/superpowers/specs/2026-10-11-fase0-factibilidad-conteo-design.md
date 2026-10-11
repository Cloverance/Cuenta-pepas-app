# Fase 0 — Prueba de factibilidad del conteo de pepas

- **Fecha:** 2026-10-11
- **Estado:** Borrador para revisión
- **Proyecto:** Cuentapepas
- **Alcance de este documento:** primer subproyecto del corte vertical "pepas de punta a punta"

## 1. Por qué empezamos por aquí

Cuentapepas quiere contar en tiempo real, sobre video, las pepas de café de cada planta, clasificarlas por madurez y estimar la cosecha por lote con su incertidumbre. Todo debe correr offline en un Android.

Antes de construir la app hay que responder con datos cuatro preguntas. Si alguna sale mal, cambia el producto:

1. **Detección.** ¿Un detector pequeño con licencia permisiva encuentra pepas y separa su madurez en las condiciones colombianas (sombrío, ladera, luz variable, frutos encimados)?
2. **Conteo único.** ¿El seguimiento entre cuadros cuenta cada pepa visible una sola vez? ¿Con qué error, qué tasa de doble conteo y qué tasa de pérdidas, y cómo cambian con la velocidad, el desenfoque y la luz?
3. **Rendimiento.** ¿El detector corre de forma sostenida en el Galaxy S22+, a cuántos cuadros por segundo, con qué temperatura y con qué consumo de batería?
4. **Visibles vs. reales.** ¿El conteo de la app se relaciona lo bastante bien con el conteo manual por planta como para calibrar un factor con un intervalo útil?

Esta fase **no construye la app**. Produce un pipeline offline en Python sobre videos grabados con el S22+, un benchmark del modelo en el S22+ y un informe con una decisión: **seguir, ajustar o pivotar**.

### Hoja de ruta completa

Cada subproyecto tendrá su propio ciclo de especificación, plan e implementación.

| # | Subproyecto | Depende de |
|---|---|---|
| **0** | **Factibilidad del conteo (este documento)** | — |
| 1 | App Android: cámara en vivo, detector, tracker, conteo por planta y recorrido, avisos de calidad, sesiones y GPS | 0 |
| 2 | Calibración visibles→reales y estimación de cosecha por lote con intervalos y cobertura del recorrido | 0, 1 |
| 3 | Floración: intensidad o conteo | 1 |
| 4 | Estado general de la planta: defoliación, vigor, edad o zoca | 1 |

## 2. Principios no negociables

Estos principios aplican desde la Fase 0. Siguen la metodología de evidencia de coffeevision y las buenas prácticas de almendra.

1. **Sin calibración no hay cosecha.** Mientras no exista un factor calibrado con su intervalo, toda cifra se reporta como **"pepas visibles"**.
2. **Siempre con intervalo.** Ninguna estimación de conteo, factor o cosecha se reporta como un número suelto.
3. **No se interpola.** Lo que no se recorrió o no se muestreó queda como "sin dato". Ausencia de dato no es cero.
4. **Mínimos de evidencia.** Un estrato (lote, condición) con menos muestras que el mínimo definido no reporta estimación; reporta "evidencia insuficiente".
5. **Trazabilidad.** Todo resultado se puede rastrear hasta el video, la versión de la taxonomía, el manifiesto del dataset (con sus hashes), la versión del código, la configuración y la semilla.
6. **Criterios preregistrados.** Los umbrales de decisión de la §9 se fijan **antes** de ver los resultados de prueba. Si se cambian, se deja escrito en un ADR por qué.
7. **Mide, no supongas.** Rendimiento, precisión del GPS, desenfoque y velocidad se miden en campo y en el dispositivo.
8. **Licencias limpias.** El repositorio es MIT. Nada de lo que se distribuya (código, pesos, datos) puede ser AGPL, GPL ni tener licencia desconocida.
9. **Idioma.** La documentación va en español. El código, los identificadores, los nombres de archivo de código y los mensajes de log van en inglés.

## 3. Decisiones técnicas

Cada decisión se registra como ADR en `docs/adr/` con el formato de almendra: Estado, Fecha, Contexto, Decisión y Consecuencias. Los ADRs no se borran; uno nuevo marca al anterior como "Reemplazado por ADR-NNNN".

| ADR | Decisión | Razón |
|---|---|---|
| 0001 | Registrar las decisiones con ADRs numerados | Trazabilidad de decisiones |
| 0002 | **Política de licencias.** Prohibido en lo distribuido: Ultralytics YOLOv8/11 (AGPL-3.0), YOLOv9 oficial (GPL-3.0), BoxMOT (AGPL-3.0) y datos sin licencia. Permitido: MIT, Apache-2.0, BSD, y CC BY 4.0 con atribución en `NOTICE`. Los datos ShareAlike (CC BY-SA, ODbL) se pueden usar, pero nunca se guardan dentro del repo. Los datos NC solo sirven de referencia | Repo MIT; una dependencia AGPL obligaría a licenciar toda la app como AGPL |
| 0003 | **Taxonomía de madurez v1** (ver §4) con índices inmutables | Etiquetas comparables entre versiones |
| 0004 | **Detector multiclase YOLOX (Apache-2.0)**, variantes Nano o Tiny, preentrenado en COCO. Alternativa si la exportación o el entrenamiento fallan: la reimplementación MIT de YOLOv9 (MultimediaTechLab/YOLO) | Licencia permisiva, arquitectura simple, exportación probada ONNX→TFLite. El detector va detrás de una interfaz para poder cambiarlo |
| 0005 | **Tracker propio estilo ByteTrack**, independiente de la clase, con **compensación del movimiento de la cámara** (al estilo de BoT-SORT). Escrito en NumPy puro, sin dependencias pesadas | ByteTrack y BoT-SORT son MIT. Escribirlo propio y simple permite portarlo a Kotlin en la Fase 1 y usar sus salidas como casos de prueba de referencia |
| 0006 | **Madurez por track, no por cuadro.** Cada track acumula las probabilidades de clase de sus detecciones y su clase final es la de mayor probabilidad acumulada | Un cambio de clase entre cuadros no debe partir un track ni duplicar el conteo; votar en el tiempo reduce el ruido de la luz |
| 0007 | **Destino móvil propuesto:** Kotlin nativo + CameraX + LiteRT (TFLite) con delegado GPU. Queda en estado *Propuesto*; se confirma o se reemplaza con el benchmark de la §8 | Procesar video cuadro a cuadro necesita la menor sobrecarga posible. Flutter añade un puente por cuadro |
| 0008 | **Política de evidencia** (principios 1 a 4 de la §2) | Honestidad estadística |

## 4. Taxonomía de madurez v1

Va en el archivo `data/taxonomy.yaml`, con `schema_version: 1`. Los índices son inmutables: una clase nueva solo se agrega al final y cualquier cambio sube `schema_version`. Cada etiqueta guarda la versión de la taxonomía con la que se hizo.

| index | id | Nombre local | Descripción visual |
|---|---|---|---|
| 0 | `green` | verde | Fruto verde, sin viraje de color |
| 1 | `semi_ripe` | pintón | Viraje parcial: amarillo, naranja o rojo en menos de ~75 % de la superficie |
| 2 | `ripe` | maduro / cereza | Rojo (o amarillo en variedades amarillas) uniforme, turgente |
| 3 | `overripe` | sobremaduro | Rojo oscuro o vinotinto, puede verse arrugado |
| 4 | `dry` | seco | Café o negro, seco en la rama |

`diseased` (broca, antracnosis) **queda fuera de la v1**: no se puede etiquetar con consistencia a distancia de caminata. Los datasets que la traen la mapean a `null` (se descarta) o a la clase de color correspondiente, según su ficha. La guía de etiquetado (`docs/protocolos/guia-etiquetado.md`) incluye fotos de ejemplo de cada clase y de los casos frontera.

## 5. Datos

### 5.1 Datos públicos: solo para entrenar

**Se usan** para el preentrenamiento y el arranque del detector, y el dataset D3 también como banco previo de tracking. Así se puede empezar a trabajar **antes de ir a campo**. **No sirven** para la evaluación final del conteo: ninguno es video de surcos en sombrío colombiano con conteo manual de referencia.

Cada fuente se registra en `data/sources/<name>.yaml`. Los campos siguen el formato de almendra: `name`, `title`, `url`, `provider`, `license`, `commercial_use`, `status` (usable / reference / blocked), `species`, `capture`, `approx_images`, `format`, `download`, `class_map`, `notes`. Además, cada fuente tiene una ficha en `docs/datos/fichas/`. El proyecto **nunca redistribuye** datasets de terceros: solo guarda los scripts para descargarlos y el manifiesto con sus hashes.

Inventario inicial: ver §5.4.

### 5.2 Datos propios de campo

Son imprescindibles para evaluar y para adaptar el modelo al dominio. Se capturan siguiendo `docs/protocolos/captura-campo.md`:

- **Muestra.** Al menos 20 plantas (meta: 30) en 1–2 fincas y al menos 2 lotes, estratificadas por carga (baja, media, alta) y por luz (sol, sombra). Cada planta se marca con una cinta numerada que aparece en el video al inicio de su clip.
- **Grabación.** Se usa el S22+ en video 1080p. En un subconjunto se comparan dos ajustes: 30 fps automático contra modo Pro con obturador de 1/250 s, para medir cómo bajan el desenfoque y la luz. Se graba **un clip por cada lado de cada planta**, caminando a 50–80 cm.
- **Velocidad.** En un tramo marcado de 10 m se graban tres velocidades: lenta (~0,3 m/s), normal (~0,6 m/s) y rápida (~1 m/s). La velocidad se mide con distancia sobre tiempo, no se estima a ojo.
- **Repetición.** Cada planta de un subconjunto se graba dos veces en pasadas separadas. Así se mide la repetibilidad del conteo, y esos datos sirven de base para diseñar en la Fase 1 cómo evitar duplicados al repetir un recorrido.
- **GPS.** Un registrador GPX corre en paralelo y guarda posición, precisión horizontal y hora cada segundo. Con eso se mide la **precisión real del GPS bajo sombrío** y se compara con la distancia entre plantas.
- **Conteo manual (verdad de terreno para la calibración).** Siguiendo `docs/protocolos/conteo-manual.md`:
  - En cada planta se cuenta el **total de pepas**, rama por rama, con contador manual.
  - En 3 ramas por planta (alta, media y baja) se cuenta además **por clase de madurez**. Se adapta el formulario ODK de Croppie (D12).
  - En un subconjunto de plantas, una segunda persona repite el conteo para medir el error humano.
  - Se registran el operador, la hora y el tiempo empleado.
- **Privacidad.** Las coordenadas de las fincas no se publican. Los datos propios se guardan fuera de git, en `data/local/` (excluido con gitignore). En git solo van los manifiestos con hashes y sin coordenadas exactas.

### 5.3 Etiquetado

La herramienta es CVAT (licencia MIT), que puede correr en local. Se hacen dos tipos de etiqueta:

- **Etiquetas de detección.** Unos 300–500 cuadros muestreados de los videos propios, con cajas y clase.
  - Se pre-etiquetan con el modelo entrenado en datos públicos y luego se corrigen a mano.
  - **La división train/val/test se hace por planta, nunca por cuadro**, para que no se filtren cuadros casi idénticos entre conjuntos.
- **Tracks de referencia (verdad de terreno para el conteo).** De 10 a 20 clips de 10–20 s que cubran las distintas condiciones.
  - Cada pepa visible se etiqueta como un track con ID único, usando la interpolación de CVAT entre cuadros clave a 10 fps.
  - Es la parte más costosa del etiquetado y es lo que hace posible medir el doble conteo y las pérdidas.

### 5.4 Inventario de datasets públicos (búsqueda del 2026-10-11)

Ningún dataset público trae video con IDs de seguimiento, ni imágenes emparejadas con conteos manuales por planta, ni condiciones de sombrío colombiano. Por eso los datos de campo de la §5.2 siguen siendo imprescindibles.

| ID | Dataset | Licencia | Contenido | Uso en la Fase 0 | Estado |
|---|---|---|---|---|---|
| D1 | [Croppie coffee UG](https://huggingface.co/datasets/rgautroncgiar/croppie_coffee_ug) (Alliance Bioversity & CIAT) | CC BY-SA 4.0 (la ficha también enlaza a GPLv3, hay que aclararlo) | 633 imágenes, ~61.000 cajas amodales en YOLO; Uganda, tomadas con celular en la rama | Base de detección densa | usable, con ShareAlike |
| D2 | [Coffee Fruit Maturity](https://universe.roboflow.com/ciencia-cafeto/coffee-fruit-maturity-befkg) (Ciencia Cafeto, Roboflow) | CC BY 4.0 en Roboflow, pero CC BY-NC 4.0 en [Kaggle](https://www.kaggle.com/datasets/cienciacafeto/coffee-fruit-maturity) | 996 imágenes con polígonos de las 5 clases más `diseased`; arábica en planta; ~74 % verdes | Fuente principal de las clases de madurez | usable\*, confirmar la licencia con el autor |
| D3 | [pjramg/colombian_coffee](https://huggingface.co/datasets/pjramg/colombian_coffee) | Apache-2.0 (solo en el encabezado YAML, sin README) | 1.593 imágenes de 1920×1080 tomadas en Colombia en 2015, en secuencias por rama; COCO con ~2.471 instancias en ~607 imágenes | Validación en dominio colombiano y **banco previo de tracking**: se le añaden IDs de track en CVAT | usable\*, confirmar procedencia |
| D5 | [seongchan-park/coffee-fruit](https://universe.roboflow.com/seongchan-park/coffee-fruit) | CC BY 4.0 | 1.785 imágenes; Verde, Pintón, Rojo, Sobremaduro | Volumen extra | usable, verificar que sean cajas |
| D7 | [tesis-cafetera-xd/cafe-9ods4](https://universe.roboflow.com/tesis-cafetera-xd/cafe-9ods4) | CC BY 4.0 | 3.751 imágenes; mismas clases que D2 | Volumen extra | usable, deduplicar contra D2 |
| D4 | [Coffee and Cashew Nut (Mendeley)](https://data.mendeley.com/datasets/r46c6bpfpf/1) | CC BY 4.0 | 3.000 imágenes tomadas con dron; ~127.000 cajas | Solo si falta robustez con objetos pequeños | usable, fuera de dominio |
| D9 | [andressac/analyzing-coffe-maturity](https://www.kaggle.com/datasets/andressac/analyzing-coffe-maturity) | CC BY 4.0 | ~5.000 imágenes de clasificación, sin cajas | No se usa en la Fase 0 | usable, solo clasificación |
| D12 | [Croppie Colombia/Perú](https://github.com/j-river1/Croppie) | El repositorio es CC0; los datos se entregan por solicitud | Conteos manuales por rama en 2.450 árboles de Cauca y Quindío | **Solicitarlo**: es el único con conteo de referencia en Colombia. El protocolo ODK se reutiliza en `conteo-manual.md` | reference |
| — | D2 en Kaggle, luisdanielc, Coffee3D | Licencias NC | — | Ninguno | reference |
| — | Roboflow sin licencia, Kaggle "Unknown", lonlonago (re-venta de D2) | Sin licencia o con licencia dudosa | — | Ninguno | blocked |

**Mapeo de clases.** Cada fuente declara su `class_map` en el archivo `data/sources/<name>.yaml`. Los casos especiales son:

- D1: `yellow_cherry` es pintón **solo** en variedades rojas, y `dark_brown_cherry` junta sobremaduro con seco. Por eso D1 se usa para la detección de "pepa" y para las clases `green`, `ripe` y `semi_ripe`. Sus cajas `dark_brown` no se usan para separar `overripe` de `dry`.
- D2 y D7: `diseased` se mapea a `null` y se descarta.
- D1: `low_visibility_unsure` se ignora.

**Higiene de datos:**
- Se deduplica con hash perceptual entre fuentes y por el nombre original que conserva Roboflow (`<nombre>_jpg.rf.<hash>`).
- De Roboflow se descargan versiones sin aumentación.
- El test propio nunca mezcla imágenes de fuentes públicas.

**Convención de cajas.** Se usan cajas **amodales**: una pepa con al menos ~10 % visible se marca con una caja que abarca el fruto completo estimado. Es la convención de D1, que es la mayor fuente densa. Así queda escrito en la guía de etiquetado.

**ShareAlike (D1).** En la Fase 0 se entrena con D1 de forma interna, sin distribuir nada. Se mide una ablación: el mismo modelo con D1 y sin D1. Antes de distribuir pesos en la Fase 1 se escribe un ADR que decide, con esa ablación en la mano, si los pesos distribuidos llevan datos ShareAlike.

**Gestiones que le corresponden al responsable del proyecto:**
1. Crear cuentas de Roboflow y Kaggle con claves API. Las claves van en variables de entorno, nunca en git.
2. Escribir a Ciencia Cafeto para confirmar la licencia de D2.
3. Escribir a la autora de D3 para confirmar su procedencia.
4. Solicitar el dataset de Croppie Colombia/Perú (D12).

## 6. Pipeline offline (Python)

Se usa uv con Python 3.12 fijado en `ml/.python-version`, por compatibilidad con PyTorch y CUDA. El código vive en `ml/src/cuentapepas/` y cada módulo tiene una sola responsabilidad y una interfaz pequeña:

| Módulo | Responsabilidad | Interfaz |
|---|---|---|
| `taxonomy` | Carga y valida `taxonomy.yaml`; mapea las clases de cada fuente | `load_taxonomy()`, `map_class(source, name)` |
| `data` | Catálogo de fuentes, conversión a COCO, unión de datasets, división por planta, manifiestos JSONL con sha256 | CLI `cuentapepas data ...` |
| `quality` | Métricas por cuadro: desenfoque (varianza del laplaciano), luz (luminancia media y % de píxeles saturados o subexpuestos) y movimiento (desplazamiento mediano del flujo óptico, en px/cuadro normalizado por el ancho) | `frame_quality(frame, prev) -> FrameQuality` |
| `detect` | Envoltorio de YOLOX: entrenamiento, inferencia y exportación ONNX→TFLite (FP16 e INT8) | `Detector.predict(frame) -> Detections` |
| `track` | Filtro de Kalman, asociación en dos etapas al estilo ByteTrack y compensación del movimiento de la cámara (afín con RANSAC sobre puntos LK) | `Tracker.update(detections, frame) -> list[Track]` |
| `count` | Confirmación de tracks (mínimo de impactos y confianza media), voto de madurez por track, conteo por clip y por planta | `Counter.finalize() -> CountResult` |
| `evaluate` | Métricas de detección, de conteo y de calibración (§7) | `cuentapepas eval ...` |
| `runs` | Cada ejecución guarda en `runs/<id>/` su configuración, commit de git, hash del manifiesto, semilla y `metrics.json` | — |

**Entrenamiento.** Este PC tiene una GTX 1050 de 3 GB, que alcanza para YOLOX-Nano/Tiny con lotes pequeños y es útil para iterar. Los entrenamientos completos se hacen en Kaggle o Google Colab (GPU T4) con el mismo código y la misma configuración. Los pesos resultantes no se versionan en git; se registran en el manifiesto con su hash.

**Resolución de entrada.** Una pepa mide unos 12–15 mm. Estimación a validar: a 50–80 cm, con 640 px de ancho, cada pepa ocupa unos 10–12 px. Por eso se comparan 640 y 960 px (y mosaicos si hace falta), midiendo la precisión y la latencia.

## 7. Evaluación

Hay tres niveles. Todos se reportan con intervalos de confianza por bootstrap (remuestreando **plantas o clips**, no cuadros), con el n de cada uno.

### 7.1 Detección (test propio colombiano, separado del test público)
- mAP50 y mAP50-95, AP y recall por clase, y recall según el tamaño de la caja.
- Matriz de confusión de madurez.
- Brecha de dominio: el mismo modelo evaluado en el test público y en el test propio.

### 7.2 Conteo de pepas visibles (contra los tracks de referencia)
Los tracks predichos y confirmados se emparejan con los tracks de referencia por IoU cuadro a cuadro, con asignación mayoritaria en el tiempo.
- **Error de conteo por clip:** (predicho − real) / real. Se reportan el sesgo, el MAE y el MAPE.
- **Tasa de doble conteo:** Σ max(0, k−1) / predichos, donde k es el número de tracks confirmados asignados a un mismo track real.
- **Tasa de pepas perdidas:** tracks reales sin ningún track confirmado asignado / reales.
- **Tasa de falsos positivos:** tracks confirmados sin track real / predichos.
- **Exactitud de madurez por track** y matriz de confusión a nivel de track.
- HOTA e IDF1 con TrackEval (MIT), para comparar con la literatura.
- **Sensibilidad:** cada métrica vista contra la velocidad, el desenfoque, la luz y el ajuste de cámara. De estas curvas salen los **umbrales de los avisos** de la Fase 1.

### 7.3 Visibles vs. reales (contra el conteo manual por planta)
- Estimador de razón k = Σ manual / Σ app, y regresión lineal manual ~ app. Con bootstrap sobre plantas se obtienen el IC de k y el **intervalo de predicción** para una planta nueva.
- Validación dejando una planta fuera (LOOCV): error por planta del conteo calibrado.
- Variabilidad entre pasadas: diferencia de conteo entre dos pasadas de la misma planta.
- El factor queda **ligado a la versión del protocolo** (un lado o dos lados, distancia, ajuste de cámara). Si el protocolo cambia, el factor no vale.
- Con unas 20–30 plantas, este resultado **solo indica factibilidad**. La calibración de producción es parte del subproyecto 2.

### 7.4 GPS
- Distribución de la precisión horizontal bajo sombrío y a cielo abierto (mediana y p90).
- Se compara con la distancia entre plantas. De esa comparación sale el ADR de la Fase 1 sobre cómo evitar duplicados al repetir un recorrido.

## 8. Benchmark en el S22+

Se instalan las Android platform-tools (adb) desde el sitio oficial de Google. El modelo exportado a TFLite se mide con la herramienta `benchmark_model` de LiteRT (Apache-2.0), ejecutada por adb. Todavía no se instala ninguna app.

- Se registra la variante de chip del teléfono (`ro.board.platform`), porque el S22+ viene con Snapdragon 8 Gen 1 o con Exynos 2200 según el mercado.
- **Matriz de pruebas:** modelo (Nano o Tiny) × resolución (640 o 960) × precisión (FP16 o INT8) × delegado (CPU de 4 hilos, GPU, NNAPI).
- **Latencia:** mediana y p90 en ms.
- **Prueba sostenida de 15 minutos** con la mejor configuración. Cada 10 s se registran la latencia, el estado térmico (`dumpsys thermalservice`) y la temperatura y nivel de batería (`dumpsys battery`). De ahí salen la caída de rendimiento por calor y el consumo estimado en % de batería por hora.
- **Limitación conocida:** el S22+ es gama alta de 2022. Si hay un teléfono de gama media disponible, se repite en él la prueba con la mejor configuración. Si no, el informe lo declara como pendiente.
- El costo del tracker y de la cámara en el teléfono se mide en la Fase 1. Aquí solo se mide el detector.

## 9. Criterios de decisión (preregistrados)

Se evalúan sobre los datos de **test** a velocidad normal. Los parámetros (umbrales de confianza, mínimo de impactos, etc.) se ajustan solo con validación.

| Pregunta | Criterio para seguir |
|---|---|
| Detección | mAP50 (todas las clases) ≥ 0,70 y recall de pepas ≥ 0,75 en el test propio |
| Madurez | Exactitud "±1 clase" a nivel de track ≥ 0,90. Cuenta como acierto la clase exacta o una vecina en el orden green→semi_ripe→ripe→overripe→dry. Se reporta además la exactitud exacta |
| Conteo visible | Error absoluto mediano por clip ≤ 15 %, doble conteo ≤ 10 %, pérdidas ≤ 20 % |
| Rendimiento | ≥ 15 FPS del detector sostenidos 15 min en el S22+, sin llegar a estado térmico `SEVERE` |
| Visibles→reales | R² ≥ 0,6 entre app y manual por planta, con el intervalo de predicción reportado |

**Resultado:**
- **Seguir:** se cumplen todos los criterios. Se pasa a la especificación de la Fase 1, la app.
- **Ajustar:** se falla en uno o dos criterios. Se cambia el protocolo (velocidad, distancia, ajuste de cámara, dos lados), la resolución o los datos, y se mide de nuevo una sola vez.
- **Pivotar:** el conteo no es viable. El producto pasa a reportar un **índice de carga y la proporción de madurez** por planta, que no requiere identidad por pepa.

## 10. Estructura del repositorio

```
README.md                 # español
LICENSE                   # MIT (existente)
NOTICE                    # atribuciones: Apache-2.0, CC BY 4.0, pesos COCO
docs/
  adr/                    # 0001-...md
  superpowers/specs/      # este documento
  protocolos/             # captura-campo.md, conteo-manual.md, guia-etiquetado.md
  datos/fichas/           # una ficha por dataset
  resultados/             # informe-fase0.md
data/
  taxonomy.yaml
  sources/*.yaml          # catálogo con procedencia y licencia
  manifests/*.jsonl       # archivos + sha256 (sin coordenadas exactas)
  local/                  # gitignored: videos, imágenes, pesos
ml/
  pyproject.toml          # uv, Python 3.12
  src/cuentapepas/        # taxonomy, data, quality, detect, track, count, evaluate, runs
  configs/                # YAML de experimentos
  tests/
bench/android/            # scripts de benchmark por adb y su análisis
```

## 11. Pruebas del código

Se escriben con TDD (pytest). Ninguna prueba necesita GPU ni datos reales:

- **Tracker:** trayectorias sintéticas con cruces, oclusiones de N cuadros y movimiento global de cámara, verificando que los IDs se mantienen; detecciones con ruido.
- **Conteo:** casos construidos a mano para el doble conteo, las pérdidas y el voto de madurez.
- **Métricas:** casos con resultado conocido para las tasas de la §7.2 y los intervalos.
- **Calibración:** datos sintéticos con un factor conocido; se verifica que la cobertura del intervalo es cercana a la nominal.
- **Taxonomía y catálogo:** validación de esquema, índices inmutables y mapeo de clases por fuente.
- **Exportación:** que el modelo ONNX y el TFLite den las mismas salidas que PyTorch sobre las mismas imágenes, dentro de una tolerancia.

## 12. Fuera de alcance de la Fase 0

- La app Android, la interfaz y los avisos en vivo (solo se calculan sus umbrales).
- Las sesiones y el control de duplicados por GPS (solo se mide la precisión del GPS).
- La estimación de cosecha por lote (subproyecto 2), la floración y el estado de la planta.
- La broca y las enfermedades.

## 13. Riesgos

| Riesgo | Mitigación |
|---|---|
| El etiquetado de tracks toma mucho tiempo | Clips cortos, 10 fps con interpolación, pre-etiquetado con el modelo |
| Los datasets públicos son muy distintos a las fincas colombianas | Afinar con datos propios y medir la brecha por separado (§7.1) |
| Licencias dudosas en Roboflow (muchos datasets son copias de otros) | Estado `reference` o `blocked` salvo verificación; deduplicar por hash perceptual |
| La GTX 1050 es lenta | Kaggle o Colab para los entrenamientos completos |
| El tracker falla en racimos densos | Compensación del movimiento de la cámara, caminar más lento y criterio "Ajustar" antes de pivotar |
| El conteo manual tiene errores | Doble conteo manual en un subconjunto de plantas para medir el error humano |
| Sobreajustar los umbrales al test | Criterios preregistrados y ajuste solo con validación |
