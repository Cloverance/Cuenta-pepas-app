# ADR-0007: Destino móvil: Kotlin nativo, CameraX y LiteRT

- **Estado:** Propuesto
- **Fecha:** 2026-10-11

## Contexto

Procesar video cuadro a cuadro necesita la menor sobrecarga posible entre la cámara, el modelo y la interfaz. Un marco multiplataforma como Flutter añade un puente por cada cuadro, lo que resta tiempo a la inferencia.

## Decisión

Se propone usar **Kotlin nativo** con **Jetpack Compose** para la interfaz, **CameraX** para la cámara y **LiteRT** (TensorFlow Lite) para la inferencia, con el delegado **GPU** y **CPU** como respaldo. minSdk 26, targetSdk 35. La orientación de la app es horizontal.

Este ADR queda en estado **Propuesto**. Se confirma o se reemplaza con el benchmark de la §8 de la especificación, en el Samsung Galaxy S22+.

## Consecuencias

- La app tiene un solo código nativo, con el módulo `:core` en Kotlin/JVM puro (sin Android) para la lógica probada en el PC.
- La decisión depende de la medición en el S22+: si el delegado GPU no sostiene el rendimiento, se evalúa el respaldo en CPU o un cambio de destino.
- Si el benchmark no cumple el criterio de rendimiento (§9, al menos 15 FPS sostenidos), este ADR se reemplaza.
