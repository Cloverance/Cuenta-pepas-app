# ADR-0003: Taxonomía de madurez v1

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

Las fuentes de datos usan nombres y criterios distintos para la madurez del fruto del café. Para comparar resultados entre versiones de datos y de modelo, hace falta un conjunto de clases único y estable.

## Decisión

La taxonomía v1 vive en `data/taxonomy.yaml` con `schema_version: 1`. Tiene cinco clases con índice inmutable:

| index | id | Nombre local |
|---|---|---|
| 0 | `green` | verde |
| 1 | `semi_ripe` | pintón |
| 2 | `ripe` | maduro / cereza |
| 3 | `overripe` | sobremaduro |
| 4 | `dry` | seco |

- Los índices no cambian. Una clase nueva se agrega al final, y cualquier cambio sube `schema_version`.
- Cada etiqueta guarda la versión de la taxonomía con la que se hizo.
- `diseased` (broca, antracnosis) queda **fuera de la v1**. Las fuentes que la traen la mapean a `null` o a la clase de color que corresponda según su ficha.
- El orden green → semi_ripe → ripe → overripe → dry define el criterio "±1 clase" de la §9.

## Consecuencias

- Las etiquetas son comparables entre versiones, porque los índices no se reasignan.
- Las fuentes declaran su `class_map` en `data/sources/<name>.yaml`.
- D1 (Croppie UG) fusiona sobremaduro y seco en `dark_brown_cherry`, así que `dry` no tiene ejemplos de esa fuente y la separación entre `overripe` y `dry` es débil.
- Una broca o enfermedad en el campo no se clasifica en v1.
