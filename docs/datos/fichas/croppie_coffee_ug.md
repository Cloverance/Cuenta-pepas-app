# Ficha de datos: Croppie coffee Uganda (`croppie_coffee_ug`)

Fuente principal de entrenamiento del detector de pepas de café. La entrada legible por máquina está en
`data/sources/croppie_coffee_ug.yaml`; esta ficha documenta el contexto, los conteos verificados y las
limitaciones.

## Resumen

| Campo | Valor |
|---|---|
| Título | Croppie coffee Uganda |
| URL | https://huggingface.co/datasets/rgautroncgiar/croppie_coffee_ug |
| Proveedor | Producers Direct y Alliance Bioversity International & CIAT |
| Financiación | GIZ, iniciativa Fair Forward (AI for All) |
| Licencia | CC BY-SA 4.0 |
| Uso comercial | Sí |
| ShareAlike | Sí (las obras derivadas se comparten bajo la misma licencia) |
| Estado en el catálogo | `usable` |
| Especies | Arábica y robusta (mezcladas, sin etiqueta de especie por imagen) |
| Captura | Celulares de pequeños productores en finca (Uganda); partes del árbol, a veces con tabla de fondo |
| Imágenes | 8.333 (5.836 en `train`, 2.497 en `val`), recortes de 480 x 640 px |
| Formato | YOLO (`clase cx cy w h`, normalizado) |
| Anotación | Cajas sobre todos los frutos de más de 10 mm (eje longitudinal), incluidos los parcialmente visibles; guía en `annotation_guide.html` |
| Descargado | 2026-10-11 |

Nota de licencia: la ficha de Hugging Face enlaza además a una guía de GPLv3 en el campo `license_link`, pero el
texto del README declara explícitamente CC BY-SA 4.0. Se trata como CC BY-SA 4.0.

## Conteos de cajas (verificados sobre los archivos de etiquetas descargados)

Total: 104.591 cajas en `train` y 44.873 en `val` (149.464 en total).

| Clase de la fuente | Cajas (train + val) | Clase de la taxonomía v1 |
|---|---|---|
| `red_cherry` | ~75.000 (52.525 + 22.465) | `ripe` (maduro) |
| `green_cherry` | ~50.000 (34.497 + 15.215) | `green` (verde) |
| `yellow_cherry` | ~17.000 (12.066 + 4.986) | `semi_ripe` (pintón) |
| `dark_brown_cherry` | ~7.000 (5.011 + 1.959) | `overripe` (sobremaduro) |
| `low_visibility_unsure` | 740 (492 + 248) | descartada (`null`), menos de 0,5 % de las cajas |

La clase `dry` (seco) de la taxonomía no recibe datos de esta fuente: Croppie fusiona sobremaduro y seco en
`dark_brown_cherry`.

## Fuga entre `train` y `val` y su corrección

Las particiones originales `train` y `val` no son independientes. Las imágenes son recortes de fotos
originales (el nombre es `<id_original>c<n>`, por ejemplo `1687438155343c12`, y la foto completa reducida lleva
solo el id). Las dos particiones comparten 622 de los 631 ids originales: recortes de una misma foto aparecen en
ambas. Evaluar con la partición `val` original sobrestimaría el rendimiento porque el modelo vería casi las
mismas fotos al entrenar.

Corrección aplicada (`ml/src/cuentapepas/datasets/coco_build.py`):

1. Se juntan las imágenes de ambas particiones.
2. Se agrupan por id original y se asigna el split por grupo (semilla fija, 15 % de grupos a `val`), de modo
   que todos los recortes de una misma foto quedan en el mismo split.
3. Se excluyen los ids listados en `_pictures_to_exclude.txt` (`1688117174257`, `1688718305705`).
4. El manifiesto `data/manifests/croppie_v1.jsonl` registra el split y el sha256 de cada imagen para que la
   partición sea reproducible y auditable.

## Sesgos y limitaciones conocidas

- Geografía: todo el material es de Uganda. No hay café colombiano ni cultivo bajo sombrío.
- Especies: incluye robusta, cuyo fruto y racimo difieren del arábica de interés para este proyecto.
- Fondo: parte de las fotos usa una tabla de fondo, lo que no representa el caso de campo real con follaje.
- Variedades amarillas: en variedades de fruto amarillo, el amarillo es maduro, pero la fuente lo etiqueta
  `yellow_cherry` y aquí se mapea a `semi_ripe` (pintón). Esto introduce ruido en esa clase.
- Sobremaduro y seco: fusionados en `dark_brown_cherry`; se mapea a `overripe` y `dry` queda sin ejemplos.
- Cajas parciales: se anotan frutos parcialmente visibles (anotación amodal parcial), lo que afecta cómo se
  deben interpretar los falsos positivos en frutos tapados.
- Desbalance: rojo y verde dominan; sobremaduro es menos del 5 % de las cajas.

## Cómo citar

Croppie, 2024. *Croppie coffee Uganda* [conjunto de datos]. Producers Direct y Alliance Bioversity & CIAT,
licenciado bajo CC BY-SA 4.0. https://huggingface.co/datasets/rgautroncgiar/croppie_coffee_ug

El dataset nunca se redistribuye con este repositorio (`data/local/` está fuera de git). Los pesos entrenados con
estos datos se distribuyen con la atribución indicada en `NOTICE`.
