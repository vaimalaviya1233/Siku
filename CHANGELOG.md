# Changelog

Cambios de cada versión publicada de **Siku Music**. Lo que se lista aquí es lo que se NOTA usando
la app; el detalle técnico vive en `CLAUDE.md` y en los documentos de `docs/`.

## 1.2.0

La versión más grande desde el lanzamiento: el ecualizador recuerda tu sonido por dispositivo de
salida, la biblioteca deja de indexar lo que no es música, el reproductor se rediseñó de arriba
abajo y la app pesa 14 MB menos.

### Ecualizador

- **Perfiles por dispositivo de salida.** La configuración del ecualizador se recuerda por tipo de
  salida y vuelve sola al reconectar: lo último que dejaste con los cascos por cable es lo que
  vuelve con ellos, sin tener que asignar nada a mano.
- **Perfiles propios.** Se guarda el sonido completo —curva, refuerzos, preamp, limitador y
  Clarity— con un nombre. Guardar con un nombre ya usado actualiza ese perfil, avisando antes.
- **Presets y perfiles ocultables**, desde Ajustes → Reproducción → Presets y perfiles del
  ecualizador. Los de fábrica se ocultan y se restauran; los propios además se borran.
- **Clarity**, un realce de agudos con su propio interruptor y cantidad (0-6 dB). Forma parte del
  perfil, así que viaja con él y con la ruta de salida.

### Biblioteca

- **Carpetas excluidas del escaneo del dispositivo** (Ajustes → Fuentes). El escaneo indexa toda la
  música que ve el sistema, y ahí caen audios de mensajería, grabaciones de voz y sonidos de
  juegos. Ahora se pueden apagar por carpeta, con el número de canciones de cada una a la vista.
- **Una SD desmontada ya no vacía la biblioteca.** La limpieza de canciones que "ya no existen" se
  limita a los almacenamientos que de verdad se pudieron leer, así que sacar la tarjeta un rato deja
  de leerse como un borrado.
- **Pestañas abajo**, opcional desde Ajustes → Apariencia: el selector pasa a una barra flotante en
  la parte inferior, al alcance del pulgar, en vez de ir bajo la búsqueda. Con el teléfono girado
  las pestañas van siempre a un lado, con el modo encendido o no.
- **Acciones rápidas en el inicio**: aleatorio, en orden, favoritos y hasta cinco géneros que
  cambian solos cada día.
- **Aleatorio y en orden también en la pestaña Canciones**, si ocultaste la pestaña Inicio.
  Reproducen lo que estás viendo, respetando el orden y los filtros que tengas puestos.
- **"Porque escuchaste a X"** propone otros artistas de tu biblioteca que comparten género, en vez
  de más canciones del mismo artista.
- **Reproducir por origen**: sin conexión (local y descargadas) o sin descargar. Aparece solo cuando
  tienes de las dos cosas.
- La tarjeta de **Favoritos en "Seguir escuchando"** se pinta con un collage de tus portadas
  favoritas en vez de un icono sobre gris.

### Reproductor

- **La carátula viaja desde la fila que tocas** hasta el reproductor, y vuelve al cerrarlo.
- **Carátula más grande**: se sale del margen de la columna para medir contra el ancho entero de la
  pantalla, que es lo que la hacía quedarse pequeña por más aire que se recortara alrededor.
- **Transporte rediseñado**: anterior y siguiente pasan a la talla del play, que morfea de forma
  entre reproducción y pausa.
- **Desliza la barra flotante hacia abajo para detener la reproducción**, con deshacer por si fue
  sin querer; hacia arriba sigue abriendo el reproductor.
- **Barra de progreso configurable** (Ajustes → Apariencia): píldora u onda, con grosor elegible y
  vista previa en vivo.
- **Botón de reproducción redondo** en la barra flotante, opcional desde Ajustes → Apariencia.
- **Botón de reproducir y aleatorio unificados** en los detalles de artista, álbum, género y lista.
  En una lista, el menú añade además orden inverso, A-Z y Z-A.
- En el detalle de una lista, el menú ⋮ de cada fila incluye **añadir a otra lista**; en Favoritos
  el corazón sale del menú y va primero, que es la acción de esa pantalla.
- Iconos más claros para **redescargar**, **temporizador**, **añadir y quitar de favoritos** y
  **añadir canciones a una lista vacía**.

### Rendimiento

- **La app pesa 14 MB menos.** La fuente de iconos se empaqueta recortada a los iconos que la app
  dibuja de verdad: 0,24 MB en lugar de 14,9.
- **Sin tirones al abrir una canción.** El color del álbum dejó de repintar toda la app en cada
  cambio de pista, que era el origen del enganchón al abrir el reproductor.
- **Menos batería en reposo.** Las animaciones que no mueven ningún píxel ya no despiertan la
  pantalla: el reproductor abierto y quieto pasó de unos 120 despertares por segundo a menos de dos.
- **Descargas reanudables y por tramos.** Un corte a mitad de un archivo grande ya no tira lo
  descargado, y al final de una sincronización las conexiones libres se reparten dentro del mismo
  archivo en vez de quedarse paradas.
- Arranque más rápido gracias al baseline profile y al precalentado de fuentes.

### Correcciones

- El reproductor ya no se quedaba tomando los toques con la pantalla en blanco tras cerrarlo.
- La cola abría siempre por la primera canción en vez de por la que está sonando.
- La barra de progreso ondulada se veía vacía en 0:00, como si estuviera deshabilitada.
- La etiqueta de tiempo de la barra de progreso ya no se quedaba flotando al cambiar de canción.
- El banner de "Escaneando biblioteca" ya no aparece y desaparece en escaneos que no tienen nada
  que hacer, ni se queda encendido para siempre.
- Texto negro sobre fondo negro al personalizar las pestañas y la barra de herramientas.
- Los interruptores de Ajustes, las barras superiores, el aleatorio de la cola y el ecualizador al
  saturar salían con el color del fondo de pantalla en vez de con el color de la carátula.
- Los menús desplegables se confundían con lo que tapaban en temas de color apagado.
- Un grupo de una sola fila —"Tus listas" con una única lista— se dibujaba con las esquinas de una
  fila del medio, o sea cuadrado.
- La canción que suena se resalta por color y ya no pesa más que la barra del reproductor; en
  Favoritos su corazón se perdía sobre ese resaltado.
- Vaciar la cola cierra el reproductor en el mismo gesto, sin fundido a negro.
- Los colores del álbum conservan la intensidad real de la carátula.
- La foto de artista ya no se pedía por WiFi para artistas que el catálogo no tiene.

---

## 1.1.1

- Ecualizador con protección de nivel (preamp manual y limitador, los dos apagados por defecto).
- Refuerzo de graves y agudos con centro elegible.
- Color del tema fiel a la carátula.
- Motion Material 3 Expressive en toda la app.
- Auditoría funcional: 37 correcciones.

## 1.0.1

- Correcciones en la pantalla de letras y en artistas.
- Mejoras en el ecualizador.

## 1.0

- Primera versión pública.
