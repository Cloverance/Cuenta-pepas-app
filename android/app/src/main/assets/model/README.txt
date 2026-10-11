Modelo de deteccion (no versionado)
===================================

Esta carpeta debe contener, para que la app pueda detectar pepas:

  model.json                      contrato del modelo (entrada, salida, clases, posprocesado)
  cuentapepas_nano_fp16.tflite    modelo en media precision (el que se prefiere)
  cuentapepas_nano_fp32.tflite    modelo en precision completa (respaldo)

Los nombres exactos de los .tflite los dicta model.json (campo "files").

Los tres archivos los produce el pipeline de ML con:

    cuentapepas export

(Plan A, Task A6; ver ml/). Copie el contenido de la carpeta de salida aqui.
Los .tflite estan ignorados por git a proposito (*.tflite en .gitignore).

La app NO trae un modelo de relleno: si falta model.json o el .tflite, la
pantalla en vivo muestra "Falta el modelo" y no se detecta nada. Nunca se
inventan detecciones.
