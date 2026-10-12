# Guía de campo: Cuentapepas v0.1

Esta guía es para el responsable de la prueba de campo, con un Samsung Galaxy S22+. Explica cómo instalar la app, cómo recorrer las plantas, cómo hacer la calibración, cómo leer las cifras y qué enviar de vuelta.

La app es una versión de prueba. Sus cifras son **pepas visibles** y no pepas reales ni kilos, salvo que el lote tenga una calibración con conteo manual (sección 7). Todos los números vienen con su intervalo.

---

## 1. Instalar el APK

1. En el S22+, abrir **Ajustes → Seguridad (o Seguridad y privacidad) → Instalar apps desconocidas**. Elegir la aplicación desde la que se abrirá el APK (Archivos, Chrome o Drive) y activar **Permitir desde esta fuente**.
2. Copiar el archivo `app-debug.apk` al teléfono por cable USB (carpeta *Descargas*) o desde Google Drive.
3. Abrir el archivo desde *Mis archivos* o *Descargas* y tocar **Instalar**.
4. Al terminar, puede desactivar de nuevo **Instalar apps desconocidas**.

La app pide permiso para la cámara, la ubicación y, si se usa el modo evaluación, para grabar video. Hay que aceptarlos todos; sin cámara ni GPS la app no funciona.

Antes de salir al campo, cargar el teléfono y revisar que el GPS esté activado en modo de alta precisión.

---

## 2. Crear un lote

En **Inicio**, tocar **Nuevo lote** e indicar:

- **Nombre** del lote (por ejemplo, *Lote Alto 2*).
- **Número total de plantas** del lote. La estimación del lote usa este número como N.
- **Pepas por kg** (opcional). Solo se llena si se midió en la finca. Sin este dato la app no estima kilos.

---

## 3. Crear un recorrido

En **Nuevo recorrido** se indica:

| Campo | Qué poner |
|---|---|
| Lote | El lote creado en la sección 2. |
| Surco | Número o nombre del surco. |
| Lado | **A** o **B**, según el lado del surco que se recorre. Usar siempre la misma convención en todo el lote. |
| Primera planta | Número de la primera planta que se va a contar. |
| Dirección | **+1** si la numeración sube al caminar, **−1** si baja. |
| Planta de muestra para calibración | Marcar solo en las plantas que se contarán a mano (sección 5). |
| Modo evaluación | Marcar para grabar el video y el registro de cada cuadro. Recomendado en las pruebas de campo. |

Después tocar **Empezar** para abrir la cámara en vivo.

---

## 4. Cómo recorrer

### Protocolo de grabación

- **Teléfono en horizontal.** La app solo funciona en esta orientación.
- **Distancia de 50 a 80 cm** entre el teléfono y la planta.
- **Caminar a unos 0,5 metros por segundo**, a lo largo del surco. Un paso tranquilo y parejo. No correr.
- **Cuando se cambia de planta**, tocar **Siguiente planta** en la pantalla o presionar la **tecla de volumen**. Así la app marca el inicio de la planta nueva.
- **Hacer pausas** si hace falta, con **Pausa**.
- **Terminar** el recorrido con **Terminar** al terminar el surco.

El lado (A o B) que se recorre debe quedar escrito en el formulario del recorrido para cada surco.

### Qué se ve en la pantalla

- **Cajas de color** alrededor de cada pepa detectada. El color indica la madurez estimada (verde, pintón, maduro, sobremaduro o seco).
- **Conteo** de la planta actual por clase y total del recorrido.
- **Avisos de calidad** (ver la sección siguiente).
- **FPS** (cuadros por segundo), **estado térmico** y **batería**.

### Avisos de calidad

| Aviso | Qué significa | Qué hacer |
|---|---|---|
| **Va muy rápido** | Se camina más rápido que lo previsto. Las pepas cruzan la imagen demasiado rápido para seguirlas bien. | Caminar más despacio, cerca de 0,5 m/s. |
| **Imagen borrosa** | El teléfono se mueve o la imagen está desenfocada. | Sujetar el teléfono con las dos manos, pegarlo al cuerpo y caminar parejo. |
| **Poca luz** | La escena está muy oscura. | Recorrer con mejor luz si es posible, y anotarlo en la bitácora. |
| **Contraluz** | El sol está detrás de las plantas y la imagen tiene zonas muy claras y muy oscuras. | Anotar el recorrido en la bitácora. |

Los umbrales de estos avisos son **provisionales**. Se ajustarán con los datos de campo. Si un aviso aparece casi siempre, anotarlo en la bitácora, porque eso es un dato para la prueba.

### Estado térmico, batería y GPS

- **Estado térmico.** Si el teléfono se calienta, el procesamiento se hace más lento y los FPS bajan. Si se calienta mucho, pausar y dejar enfriar.
- **Batería.** Anotar el porcentaje al inicio y al final de cada recorrido. El benchmark de la sección 8 calcula el consumo por hora.
- **GPS.** Los puntos con precisión peor que 10 m se descartan. El GPS sirve para el trazado y la cobertura, pero **no identifica plantas ni surcos vecinos**: bajo sombrío su error puede ser mayor que la distancia entre plantas. Por eso la clave que manda es la que se escribe en el formulario de la sección 3, no el GPS.

---

## 5. Calibración: plantas de muestra

La calibración convierte "pepas visibles" en una estimación de pepas reales. Sin calibración, la app solo muestra pepas visibles.

### Cómo se hace

1. Elegir **al menos 10 plantas de muestra** dentro del lote. Repartirlas entre cargas baja, media y alta, y entre sol y sombra, si el lote lo permite.
2. Marcar cada una como **planta de muestra para calibración** al recorrerla (sección 3).
3. Contar **a mano** todas las pepas de cada planta de muestra, **completa, rama por rama**, con un contador manual. Anotar el total de cada planta.
4. En la pantalla **Calibración**, ingresar el conteo manual de cada planta de muestra.

El factor de calibración del protocolo v1 es **"un lado → planta completa"**: se cuenta un lado de la planta con la app y el conteo manual cubre la planta entera. Si se cambia el protocolo (por ejemplo, grabar dos lados, cambiar la distancia o el ajuste de cámara), el factor anterior **deja de valer** y hay que calibrar de nuevo.

### Qué muestra la pantalla de Calibración

- **k**, la razón entre el conteo manual y el de la app, con su intervalo de confianza al 90 %.
- **n**, el número de plantas de muestra.
- El **error por planta** dejando cada una fuera, para ver si el factor es estable.

La calibración **solo se activa con n ≥ 10**.

---

## 6. Estimación del lote

En **Estimación del lote** la app muestra uno de estos tres estados:

| Estado | Cuándo | Qué se muestra |
|---|---|---|
| **Evidencia insuficiente** | Menos de 10 plantas recorridas. | No hay estimación. |
| **Solo pepas visibles** | Hay 10 o más plantas recorridas, pero no hay calibración. | Promedio de pepas visibles por planta, con intervalo. |
| **Calibrado** | Hay calibración con n ≥ 10. | Total estimado del lote con intervalo al 90 %, proporción de madurez con intervalo, y kilos si se ingresaron pepas por kg. |

Siempre aparece la **cobertura**: plantas recorridas de N, surcos y lados recorridos, y la advertencia **"no recorrido = sin dato"**.

Reglas que no se deben olvidar:

- **No se interpola.** Los surcos que no se recorrieron no se cuentan como cero ni se estiman a partir de los vecinos.
- El total supone que **las plantas recorridas representan al lote**. Si se recorrió solo una parte del lote, el total describe esa parte, y la pantalla lo dice junto a la cobertura.
- Con **"solo pepas visibles"**, la cifra no es una cosecha. Compararla entre lotes es válido, pero no como kilos.

---

## 7. Recorridos repetidos

- Si se vuelve a recorrer el **mismo lote, surco y lado**, la app pregunta si **reemplazar** el recorrido anterior o **cancelar**.
- Los conteos **nunca se suman**. Para cada planta cuenta el recorrido más reciente.
- Si una misma planta tiene los dos lados recorridos, cada lado da una estimación calibrada independiente de la planta y la app **promedia** las dos.
- El **aviso de recorrido repetido por GPS** es solo orientativo. Se activa si al menos el 70 % de los puntos buenos del recorrido nuevo quedan a 3 m o menos de otro recorrido del mismo lote. Cuando aparezca, confirmar a mano con la clave de surco, lado y planta.

---

## 8. Benchmark de 15 minutos

El benchmark mide cómo se comporta el teléfono con el detector durante un uso largo.

1. Abrir **Benchmark** en la app.
2. Dejarlo correr los **15 minutos** completos sin interrumpirlo.
3. Al terminar, anotar en la bitácora:
   - **FPS** mediano y el p10 (el 10 % más lento).
   - **Estado térmico máximo** que se alcanzó.
   - **Porcentaje de batería** consumido por hora (la app lo calcula a partir del inicio y el final).
4. Exportar el resultado del benchmark (json) para enviarlo.

---

## 9. Qué exportar y enviar

Al terminar la prueba, enviar al equipo del proyecto:

1. **Las sesiones.** En el resumen de cada recorrido, botón **"Exportar (zip)"**. El zip trae `meta.json`, `plants.json`, `plants.csv`, `gps.jsonl`, `stats.json` y, si se usó el modo evaluación, `frames.jsonl` (registro por cuadro).
2. **Los videos del modo evaluación**, con el botón **"Compartir video"** del mismo resumen. Si la app pasó a segundo plano durante el recorrido, el video viene en varias partes (`<id>.mp4`, `<id>_1.mp4`, …): enviarlas todas.
3. **El json del benchmark** de 15 minutos.
4. **La bitácora de campo** (texto libre): fecha, finca, lote, condiciones de luz, avisos que aparecieron con frecuencia, incidencias y tiempo que tomó cada recorrido.

No enviar coordenadas exactas de la finca por canales públicos. Los datos propios se guardan fuera de git.

---

## 10. Limitaciones conocidas de la versión 0.1

- **Entrenado solo con datos de Uganda** (Croppie coffee UG). No hay todavía datos colombianos, ni café bajo sombrío, ni arábica de la zona.
- **Desempeño medido en validación (fotos de Uganda, no de campo colombiano):** mAP50 = 0,50; recall de pepas = 0,71 (0,44 en pepas pequeñas en la imagen, 0,88 en medianas). AP50 por clase: verde 0,66, pintón 0,29, maduro 0,72, sobremaduro 0,32, seco sin datos. En campo colombiano puede ser peor: por eso existen el modo evaluación y la calibración.
- **Pepas pequeñas en la imagen se pierden más:** acercarse a 50 cm mejora la detección.
- Las clases **sobremaduro** y **seco** vienen fusionadas en el dataset de origen. Por eso la separación entre ellas es débil, y la clase **seco** no tiene ejemplos de entrenamiento.
- Las variedades de **fruto amarillo** pueden confundirse en la clase pintón.
- El modelo incluye ejemplos de **robusta**, cuyo fruto y racimo son distintos del arábica.
- Algunas fotos del dataset tienen **tabla de fondo**, que no representa bien el follaje real.
- Los **umbrales de los avisos** y de confirmación de tracks son **provisionales**.
- **Sin evaluación de campo todavía.** El desempeño en el S22+ y la precisión del conteo frente al conteo manual se miden con esta prueba.
- La **broca y las enfermedades** quedan fuera de la v1: el modelo no las clasifica.
- Las cifras son de **pepas visibles**. Una pepa tapada por hojas o ramas no se cuenta, y una pepa que se repite al recorrer el surco puede contarse dos veces si el recorrido no se reemplaza (sección 7).
