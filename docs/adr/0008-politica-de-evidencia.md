# ADR-0008: Política de evidencia

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

Un conteo de pepas visibles no es una cosecha, y una estimación sin intervalo o sin cobertura puede parecer más precisa de lo que es. Las plantas o los surcos que no se recorrieron no son cero. Un estrato con muy pocas muestras no permite estimar nada útil.

## Decisión

Se aplican los principios 1 a 4 de la especificación (§2):

1. **Sin calibración no hay cosecha.** Mientras no exista un factor calibrado con su intervalo, toda cifra se reporta como **"pepas visibles"**.
2. **Siempre con intervalo.** Ninguna estimación de conteo, factor o cosecha se reporta como un número suelto.
3. **No se interpola.** Lo que no se recorrió o no se muestreó queda como "sin dato". Ausencia de dato no es cero.
4. **Mínimos de evidencia.** Un estrato con menos muestras que el mínimo no reporta estimación y muestra "evidencia insuficiente". El mínimo es de 10 plantas: la calibración solo se activa con n ≥ 10 y la estimación de lote muestra "evidencia insuficiente" con menos de 10 plantas recorridas (§14.2).

Además, la pantalla de estimación de lote siempre muestra la cobertura (plantas, surcos y lados recorridos).

## Consecuencias

- La app y los informes distinguen "pepas visibles" de una estimación calibrada.
- Ningún recorrido sin datos se convierte en cero.
- Los intervalos se calculan por bootstrap sobre plantas o clips, no sobre cuadros (§7 y §14.4).
- Un lote con pocas plantas recorridas no tiene estimación: eso es el comportamiento esperado, no un error.
