# ADR-0001: Registrar decisiones con ADRs numerados

- **Estado:** Aceptado
- **Fecha:** 2026-10-11

## Contexto

El proyecto toma decisiones que afectan la licencia, los datos, el modelo, la arquitectura y la forma de presentar las cifras. Sin un registro, no se sabe por qué se eligió algo ni cuándo cambió, y los criterios de decisión podrían ajustarse después de ver los resultados.

## Decisión

Cada decisión técnica importante se registra como un ADR en `docs/adr/`, con nombre `NNNN-slug.md` y numeración consecutiva. Cada ADR tiene estas secciones:

- **Estado:** Propuesto, Aceptado o Reemplazado.
- **Fecha.**
- **Contexto:** el problema y las restricciones.
- **Decisión:** lo que se decidió.
- **Consecuencias:** lo que cambia, lo que se gana y lo que queda pendiente.

Los ADRs no se borran. Si una decisión cambia, el ADR nuevo se escribe y el anterior se marca como "Reemplazado por ADR-NNNN". Si se cambia un criterio preregistrado de la especificación (§2, principio 6), el cambio se justifica en un ADR.

El índice está en [README.md](README.md).

## Consecuencias

- Cada decisión tiene fecha, razón y consecuencias trazables.
- Un cambio de criterio deja rastro en el repositorio y no queda oculto.
- Hay que mantener el índice al día cuando se agrega un ADR.
