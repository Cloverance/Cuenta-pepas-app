# ADR-0006: Madurez por track, no por cuadro

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

Si la clase de madurez se decide cuadro por cuadro, una pepa puede cambiar de clase entre cuadros por cambios de luz o de ángulo. Cada cambio partiría el track y podría duplicar el conteo de esa pepa.

## Decisión

Cada track acumula las probabilidades de clase de sus detecciones. Su clase final es la de mayor probabilidad acumulada.

## Consecuencias

- Un cambio de clase entre cuadros no parte el track ni duplica el conteo.
- Votar a lo largo del tiempo reduce el ruido de la luz.
- La exactitud de madurez se mide a nivel de track, y la matriz de confusión también (§7.2 y §9).
- Una pepa que se ve pocas veces tiene menos votos, y su clase es menos confiable. Solo se cuentan los tracks confirmados, con al menos `minHits` impactos y una confianza media de al menos `minScore` (§14.3).
