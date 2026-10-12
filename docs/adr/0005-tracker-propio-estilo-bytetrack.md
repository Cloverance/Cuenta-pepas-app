# ADR-0005: Tracker propio estilo ByteTrack, con compensación del movimiento de la cámara

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

El conteo único depende de que cada pepa tenga un solo ID a lo largo del video. Al caminar a lo largo del surco, la cámara se mueve, así que el movimiento global de la imagen también cambia las posiciones de las pepas. ByteTrack y BoT-SORT son MIT. BoxMOT, que los agrupa, es AGPL-3.0 y no puede usarse ([ADR-0002](0002-politica-de-licencias.md)).

## Decisión

Se escribe un tracker propio, estilo ByteTrack, independiente de la clase, con compensación del movimiento de la cámara al estilo de BoT-SORT. Se implementa en NumPy puro, sin dependencias pesadas. Las piezas son: filtro de Kalman, asociación en dos etapas (alta y baja confianza) y compensación del movimiento global por una transformación afín con RANSAC sobre puntos de flujo óptico Lucas-Kanade.

## Consecuencias

- El tracker es simple y se puede portar a Kotlin en `:core` (módulo `track`) sin depender de librerías externas.
- Sus salidas en Python sirven como casos de prueba de referencia para la versión Kotlin.
- Hay que medirlo: HOTA e IDF1 con TrackEval (MIT) y la sensibilidad a la velocidad, el desenfoque y la luz (§7.2).
- Un tracker propio puede tener más fallas que uno maduro en racimos densos. Por eso la §13 de la especificación prevé caminar más despacio y el criterio "Ajustar" antes de pivotar.
