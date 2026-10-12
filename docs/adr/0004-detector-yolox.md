# ADR-0004: Detector YOLOX (Apache-2.0)

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

La app necesita un detector pequeño, rápido en un teléfono de gama alta y con una licencia permisiva (ver [ADR-0002](0002-politica-de-licencias.md)). La exportación a TFLite tiene que estar probada. El entrenamiento se hace en una GTX 1050 de 3 GB local, con lotes pequeños.

## Decisión

- **Detector:** YOLOX (Apache-2.0), variante **Nano** (la especificación permite Nano o Tiny; la v1 usa Nano).
- **Preentrenamiento:** pesos COCO de YOLOX, con atribución en [NOTICE](../../NOTICE). El entrenamiento parte de ese checkpoint (`tools/get-pretrained.sh`).
- **Resolución de entrada fija:** **384 × 640** (alto × ancho) en el entrenamiento y en la app. La app reduce el cuadro a 640 × 360 y lo rellena hasta 640 × 384.
- **Entrenamiento:** en la GTX 1050 local, con `cuentapepas train`. El entrenamiento largo se corre de forma desacoplada con `tools/train-detached.ps1`.
- **Exportación:** PyTorch → ONNX con la decodificación incluida → onnx2tf → TFLite en float16 y float32, con verificación de paridad frente a PyTorch.
- **Alternativa:** si la exportación o el entrenamiento fallan, se usa la reimplementación MIT de YOLOv9 (MultimediaTechLab/YOLO).
- El detector va detrás de una interfaz, para poder cambiarlo.

## Consecuencias

- El modelo v1 tiene entrada de 384 × 640, con el relleno descrito arriba.
- La comparación de resoluciones 640 y 960 y los mosaicos de la §6 de la especificación no forman parte del modelo v1 y quedan pendientes.
- Los entrenamientos completos en Kaggle o Colab (GPU T4), también previstos en la §6, no se usan en la v1. El entrenamiento se hace en la GTX 1050 local.
- El tamaño de lote es pequeño por la memoria de la GTX 1050 (3 GB), y la GTX 1050 es lenta para entrenar (§13 de la especificación).
- Cambiar de detector no obliga a cambiar el resto del pipeline, porque el detector va detrás de una interfaz.
