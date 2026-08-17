package com.qhana.siku.data.util

import kotlinx.coroutines.flow.SharingStarted

/**
 * Margen que un flow compartido sigue vivo tras perder a su último colector.
 *
 * **Es el valor que recomienda Google** para `stateIn`/`shareIn` en ViewModels y el que usan sus
 * propios samples de arquitectura, así que no sale de este repo. La razón declarada es el CAMBIO DE
 * CONFIGURACIÓN: rotar destruye y recrea la UI, y entre el último `onStop` y el primer `onStart` no
 * hay nadie suscrito. Sin margen, ese instante basta para cancelar la consulta de Room y volver a
 * montarla entera al otro lado — trabajo repetido y, en las agregaciones de la biblioteca, un
 * parpadeo de lista vacía.
 *
 * **Cuidado con la explicación fácil de que "cubre la rotación": esa dura milisegundos, no cinco
 * segundos.** Es una crítica conocida al valor (Pierre-Yves Ricau escribió sobre esto): el retardo
 * es indiscriminado, frena el apagado venga la desuscripción de donde venga, y es un parche sobre
 * un desajuste de ciclos de vida más que una solución. Lo que de verdad justifica que sea tan
 * holgado es el OTRO caso que cubre de paso: salir a otra app y volver enseguida —con
 * `collectAsStateWithLifecycle`, irse a segundo plano también desuscribe—, que es cuestión de
 * segundos y no de milisegundos.
 *
 * Aquí se acepta esa crítica a sabiendas: la alternativa que propone (scopes atados a la
 * navegación en vez de al ViewModel) es un rediseño del estado de toda la app, y lo que cuelga de
 * estos flows son agregaciones `GROUP BY` sobre la biblioteca entera —justo lo que no conviene
 * repetir por un vistazo a otra aplicación—. El coste del margen es tener esas consultas
 * registradas unos segundos de más; el de no tenerlo, re-ejecutarlas.
 */
const val UI_SHARING_STOP_TIMEOUT_MS = 5_000L

/**
 * Política de compartición para todo estado que alimenta la UI. Es UNA instancia para toda la app:
 * el valor estaba escrito a mano en 25 sitios y en tres formatos distintos (`5_000`, `5000` y una
 * constante local de `AudioRouteMonitor` que sí lo explicaba), así que nada obligaba a que
 * coincidieran ni había un solo lugar donde cambiarlo.
 *
 * `SharingStarted` no guarda estado por suscriptor, así que compartir la instancia es correcto y
 * además ahorra un objeto por cada flow declarado.
 */
val WhileUiSubscribed: SharingStarted = SharingStarted.WhileSubscribed(UI_SHARING_STOP_TIMEOUT_MS)
