# ADR-0002: Política de licencias y de pesos del modelo

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

El repositorio es MIT. Si se distribuye una dependencia con licencia AGPL, la app tendría que licenciarse como AGPL. Algunas herramientas populares de detección y seguimiento usan AGPL o GPL. Además, varios datasets de café tienen licencias ambiguas o incompatibles.

El dataset principal de entrenamiento, Croppie coffee Uganda (D1), tiene licencia CC BY-SA 4.0. Su ficha en Hugging Face enlaza también a una guía de GPLv3, pero el README declara CC BY-SA 4.0 de forma explícita, y se trata como CC BY-SA 4.0 (ver `docs/datos/fichas/croppie_coffee_ug.md`). Los pesos entrenados con D1 son material adaptado de ese dataset, por lo que llevan su licencia ShareAlike.

## Decisión

**Prohibido en lo distribuido** (código, pesos o datos):

- Ultralytics YOLOv8 y YOLOv11 (AGPL-3.0).
- YOLOv9 oficial (GPL-3.0).
- BoxMOT (AGPL-3.0).
- Datos sin licencia o con licencia desconocida.

**Permitido:** MIT, Apache-2.0, BSD, y CC BY 4.0 con atribución en [NOTICE](../../NOTICE).

**Datos ShareAlike** (CC BY-SA, ODbL): se pueden usar para entrenar, pero nunca se guardan dentro del repositorio. Los datos NC (no comerciales) solo sirven de referencia.

**Pesos del modelo entrenado con D1:** se distribuyen bajo **CC BY-SA 4.0**, de forma separada del código, que sigue bajo **MIT**. La atribución de D1 y la declaración de modificaciones van en [NOTICE](../../NOTICE). Las imágenes de D1 no se redistribuyen.

La especificación (§5.4) pedía decidir la distribución de pesos ShareAlike con una ablación (el mismo modelo con y sin D1) antes de distribuir. Esa ablación no consta en el repositorio. La decisión de distribuir bajo CC BY-SA se toma con el alcance de la §14 para la app v1, y debe revisarse si la ablación muestra que D1 es necesario o si cambia el criterio de licencias.

## Consecuencias

- El código y los pesos tienen licencias distintas, y ambas aparecen en el repositorio y en `NOTICE`.
- Los pesos (`*.tflite`, `*.onnx` y `*.pth` derivados de D1) llevan CC BY-SA 4.0 y la atribución al distribuirse.
- Cualquier dependencia nueva pasa por esta política antes de entrar en el repositorio.
- Datos de D2 y D3 quedan como referencia y no se usan para entrenar (§14).
