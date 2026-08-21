package com.qhana.siku.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/**
 * Graba el baseline profile de Siku Music.
 *
 * QUÉ es: la lista de clases y métodos que Android debe compilar AOT al instalar la app, en vez de
 * interpretarlos y JITearlos durante los primeros arranques. Las librerías de Compose ya traen el
 * suyo, pero el código propio —composables, ViewModels, el DAO generado por Room— no tenía
 * ninguno, así que el primer arranque y el primer scroll de cada instalación se pagaban enteros.
 *
 * CÓMO se usa: `./gradlew :app:generateBaselineProfile`. El resultado se escribe solo
 * en `app/src/.../generated/baselineProfiles/` y **hay que commitearlo**: es un artefacto de build
 * reproducible, no un archivo temporal.
 *
 * CUÁNDO hay que regenerarlo: cuando el recorrido crítico cambie de forma (una pantalla nueva en
 * el arranque, un cambio grande de navegación). No hace falta por cada commit — un perfil algo
 * viejo sigue siendo mucho mejor que ninguno, porque lo que perfila son los caminos, no las líneas.
 *
 * **Son DOS tests y la separación es funcional, no cosmética**: [startup] alimenta además el
 * *startup profile* (el orden de las clases dentro del dex) y por eso hace SOLO el arranque, mientras
 * que [criticalJourney] recorre biblioteca, scroll, reproductor y pestañas y va solo al baseline
 * profile. Ver el KDoc de [startup].
 *
 * El recorrido es deliberadamente el del USUARIO REAL en sus primeros segundos, no una demo de todas
 * las pantallas: arrancar, ver la biblioteca, scrollear, **poner música y abrir y cerrar el
 * reproductor**, cambiar de pestaña. Meter de todo dilata el perfil y diluye lo que de verdad
 * importa optimizar.
 *
 * **Por qué el reproductor SÍ está en la lista** (se añadió el 17 ago 2026): su árbol es, de lejos,
 * el composable más grande de la app, y hasta ahora nada del recorrido lo tocaba — o sea que en cada
 * instalación nueva la primera apertura se interpretaba y JITeaba entera, justo en el frame que
 * arranca el *container transform*. El reproductor persistente quitó el coste de COMPONERLO en cada
 * apertura, pero no el de la primera vez; eso solo lo quita el AOT. Abrir el reproductor es de las
 * primeras cosas que hace cualquiera con una app de música, así que no es "una pantalla más".
 *
 * **CÓMO COMPROBAR QUE EL PERFIL SIRVE**, porque este pipeline falla EN SILENCIO por diseño (una
 * `nonMinifiedRelease` sin biblioteca se queda en el onboarding y produce un perfil que solo cubre
 * el arranque; y `assembleRelease` empaqueta el perfil si existe y compila igual si no):
 *
 * ```
 * grep -c NowPlaying app/src/release/generated/baselineProfiles/baseline-prof.txt
 * ```
 *
 * Cero ahí significa que el recorrido del reproductor no llegó a correr y el perfil no vale para lo
 * que se buscaba. El test además FALLA si ninguna iteración logró abrirlo (ver `playerCovered`).
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * **ARRANQUE, y SOLO el arranque** (`includeInStartupProfile = true`).
     *
     * Este recorrido alimenta DOS artefactos: el baseline profile (AOT) y, además, el **startup
     * profile**, que es otra cosa y por eso este test existe aparte — con él AGP decide el ORDEN de
     * las clases dentro del dex, poniendo juntas las que hacen falta para el primer frame, de modo
     * que el arranque en frío toque menos páginas. Ese reparto solo sirve si la lista es CORTA: si
     * dentro va media app, no hay nada que ordenar.
     *
     * **Ése era exactamente el fallo de la primera versión** (17 ago 2026): había un único test con
     * `includeInStartupProfile = true` que hacía el recorrido completo, así que `startup-prof.txt`
     * salía BYTE A BYTE IDÉNTICO a `baseline-prof.txt` —5,9 MB, con el reproductor entero dentro— y
     * el reparto del dex no ordenaba nada. Se detecta en un segundo, y conviene comprobarlo tras
     * cada regeneración:
     *
     * ```
     * cmp app/src/release/generated/baselineProfiles/{baseline,startup}-prof.txt   # NO deben ser iguales
     * ```
     */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        maxIterations = 8,
        stableIterations = 3,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * El recorrido CRÍTICO completo: biblioteca, scroll, reproductor, pestañas. Entra en el baseline
     * profile (todo lo que toca se compila AOT al instalar) pero **NO en el startup profile** — ver
     * [startup] para por qué esa distinción importa.
     */
    /**
     * Dónde se rindió la ÚLTIMA iteración de [exercisePlayer].
     *
     * Existe porque los cuatro puntos de rendición de ese recorrido devolvían el mismo `false`, así
     * que cuando ninguna iteración lo lograba el mensaje del `check` solo podía enumerar hipótesis —
     * y la ejecución no deja ni capturas ni logcat que reconstruyan el paso (comprobado el 20 ago
     * 2026, tras un fallo que costó una tarde de deducción sobre los TIEMPOS de los testcases). El
     * dato lo tiene el propio recorrido en el momento de fallar; solo hacía falta guardarlo.
     */
    private var lastStop: String = "no llegó a intentarse"

    @Test
    fun criticalJourney() {
        var playerCovered = false
        rule.collect(
            packageName = PACKAGE_NAME,
            // Varias iteraciones: el perfil se queda con lo que se repite, así que una pasada suelta
            // arrastraría ruido de esa ejecución concreta.
            maxIterations = 8,
            stableIterations = 3,
            includeInStartupProfile = false
        ) {
            pressHome()
            startActivityAndWait()

            // La biblioteca puede tardar en poblarse (escaneo de arranque). Se espera por CONTENIDO y
            // no con un sleep fijo: en un emulador frío el margen real varía mucho.
            //
            // Y el contenido se mide con el CHIP, no con `By.scrollable(true)`: desde que las
            // pestañas son un `PrimaryScrollableTabRow` eso último acierta al instante aunque no haya
            // ni una canción, así que la espera no esperaba nada (ver [mainList]). Cada iteración
            // arranca en frío —`BaselineProfileRule` mata el proceso— así que siempre se entra por la
            // pestaña Inicio, que es donde vive el chip.
            device.wait(Until.hasObject(By.text(PLAY_IN_ORDER_CHIP)), CONTENT_TIMEOUT_MS)

            scrollLibrary()
            // Basta con que UNA iteración lo consiga: lo que se comprueba es que el camino EXISTE.
            // El `catch` cubre lo mismo que [scrollMainList] pero para los `click`: entre encontrar
            // el chip (o la píldora) y pulsarlo, la lista de debajo puede recomponerse y llevarse el
            // nodo por delante. Una iteración perdida no es motivo para tirar la generación entera
            // —hay hasta `maxIterations`—, y si NINGUNA lo consigue ya lo dice el `check` de abajo.
            val covered = try {
                exercisePlayer()
            } catch (_: StaleObjectException) {
                lastStop = "el nodo se recompuso a mitad del gesto (StaleObjectException)"
                false
            }
            if (covered) playerCovered = true
            switchTabs()
        }
        // Falla RUIDOSAMENTE en vez de escribir un perfil que no cubre lo que promete: sin esto, un
        // device sin música produce un perfil de solo-arranque, se commitea igual, y nadie se entera
        // hasta que alguien mide (que es exactamente cómo la app llegó a agosto sin ningún perfil).
        // Se comprueba DESPUÉS de `collect` y no dentro, para no tirar la generación entera por una
        // iteración en la que un `click` no llegó a tiempo.
        check(playerCovered) {
            "El recorrido del reproductor no corrió en NINGUNA iteración. Se rindió en: $lastStop. " +
                "Un perfil generado así solo cubre el arranque."
        }
    }

    /** Scroll de la lista principal: es donde se nota el jank sin perfil. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollLibrary() {
        repeat(3) { scrollMainList(Direction.DOWN, SCROLL_PERCENT) }
        scrollMainList(Direction.UP, 1f)
    }

    /**
     * UN gesto de scroll sobre la lista principal, **re-buscando el objeto en cada llamada**.
     *
     * `UiObject2` no apunta a una vista sino a un NODO del árbol de accesibilidad, y ese nodo se
     * recrea con cualquier cambio estructural de la lista. En esta app eso pasa SOLO, sin que nadie
     * toque nada: el escaneo de arranque sigue poblando la biblioteca mientras el recorrido corre, y
     * cada lote de canciones nuevas rehace la lista. Cuando ocurre, el objeto guardado queda obsoleto
     * y cualquier método suyo lanza `StaleObjectException`.
     *
     * Guardar la referencia y reutilizarla para varios gestos —como se hacía hasta el 19 ago 2026—
     * es por tanto una carrera que se pierde tarde o temprano: el primer scroll iba bien y el
     * siguiente reventaba, tirando la generación entera después de instalar y arrancar la app.
     * Buscar de nuevo cuesta un `findObject` por gesto, que al lado de un scroll no es nada.
     *
     * @return `false` si no hay nada scrolleable, o si el nodo seguía muriéndose tras
     *   [STALE_RETRIES] intentos. El llamador decide: este recorrido prefiere seguir sin scroll a
     *   fallar, porque lo que de verdad tiene que cubrir es el reproductor.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollMainList(
        direction: Direction,
        percent: Float
    ): Boolean {
        repeat(STALE_RETRIES) {
            val list = mainList() ?: return false
            try {
                list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
                list.scroll(direction, percent)
                device.waitForIdle()
                return true
            } catch (_: StaleObjectException) {
                // El nodo murió entre el findObject y el gesto: se busca otra vez.
                device.waitForIdle()
            }
        }
        return false
    }

    /**
     * La lista VERTICAL de contenido, elegida por **tamaño** entre todos los nodos scrolleables.
     *
     * `findObject(By.scrollable(true))` devuelve el PRIMERO del árbol, y desde el 20 ago 2026 ese ya
     * no es la lista: la navegación de la biblioteca pasó a ser un `PrimaryScrollableTabRow` —un
     * componente de pestañas de verdad, que scrollea— y vive por encima del contenido, así que se
     * llevaba todos los gestos. El síntoma no era un error sino un recorrido que no encontraba nada:
     * `scrollLibrary` bajaba la lista, los cuatro "volver arriba" de [exercisePlayer] movían la fila
     * de pestañas (que no se mueve), la lista se quedaba abajo y los chips del Inicio ni siquiera
     * estaban compuestos. De ahí "no se encontró el chip En orden" con la biblioteca llena y la
     * música sonando. **Es exactamente por qué el perfil se generó bien el 19 y dejó de hacerlo el
     * 20.** Hoy hay tres scrollables en pantalla (pestañas, chips del Inicio y contenido).
     *
     * Por ALTO y no por posición en el árbol: la lista ocupa el resto de la pantalla, mientras que la
     * fila de pestañas mide ~54dp y la de chips ~40dp. No depende del orden de composición ni de
     * `testTag`s (que además exigirían `testTagsAsResourceId` en la app solo para esto).
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.mainList(): UiObject2? =
        device.findObjects(By.scrollable(true))
            .maxByOrNull { it.visibleBounds.height() }

    /**
     * Poner música y ABRIR Y CERRAR el reproductor, por sus DOS caminos, que compilan cosas distintas:
     *
     *  1. **Desde un chip de acciones rápidas** ("En orden"): arranca la reproducción y abre el
     *     reproductor sin superficie de origen (`PlayerArtOrigin.NONE`, o sea fundido). Esta pasada
     *     es la que paga la PRIMERA composición del árbol del NowPlaying entero — layouts, transporte,
     *     barra de progreso, carátula—, que es el grueso de lo que se quiere AOT.
     *  2. **Desde la píldora**: el *container transform* de verdad (`sharedBounds` + carátula
     *     anidada + `CallerManagedVisibilityScope`), que es el camino normal a partir de ahí.
     *
     * Los dos se cierran con "atrás", que es el mismo `collapsePlayer` del gesto y del botón.
     *
     * **Se ancla en TEXTO y no en posiciones** porque una coordenada depende del alto de la pantalla
     * y del contenido; y en dos idiomas porque la app tiene `values-en` y el device puede estar en
     * cualquiera de los dos. El chip elegido es "En orden" y no "Aleatorio" por una colisión real:
     * `home_ctx_shuffle` tiene EXACTAMENTE el mismo texto que `home_action_shuffle`, así que en un
     * device con historial el carrusel "Seguir escuchando" puede tener una tarjeta que dice
     * "Aleatorio" y `findObject` no garantiza cuál de las dos devuelve.
     *
     * @return `true` si se logró abrir y cerrar; `false` si el device no dio pie (sin biblioteca,
     *   otro idioma). Nunca lanza: quien decide qué hacer con eso es [generate].
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.exercisePlayer(): Boolean {
        // Los chips están en la pestaña INICIO, y no se puede dar por hecho que sea la activa: si el
        // proceso sobrevive entre iteraciones, la pestaña se queda donde la dejó [switchTabs]. Se
        // vuelve a la primera deslizando de más — en la página 0 un deslizamiento más no hace nada—,
        // que es determinista sin depender de las etiquetas de las pestañas (las inactivas son SOLO
        // el glifo, sin texto que buscar).
        repeat(TABS_TO_FIRST_PASSES) {
            device.swipe(
                device.displayWidth / GESTURE_MARGIN_DIVISOR,
                device.displayHeight / 2,
                device.displayWidth * (GESTURE_MARGIN_DIVISOR - 1) / GESTURE_MARGIN_DIVISOR,
                device.displayHeight / 2,
                SWIPE_STEPS
            )
        }
        device.waitForIdle()

        // Los chips viven arriba del todo del Inicio y [scrollLibrary] pudo dejarlos fuera de vista.
        // Varias pasadas a pantalla completa: arriba del todo un scroll más no hace nada, así que
        // pasarse es gratis y quedarse corto no.
        repeat(SCROLL_TO_TOP_PASSES) { scrollMainList(Direction.UP, 1f) }

        // Cada rendición ANOTA su paso en [lastStop]: los cinco devolvían el mismo `false` y el
        // mensaje del `check` no podía decir cuál fue.
        val chip = device.findObject(By.text(PLAY_IN_ORDER_CHIP)) ?: run {
            lastStop = "no se encontró el chip \"En orden\" en el Inicio — casi siempre la app se " +
                "quedó en el onboarding (device sin biblioteca), o el idioma no es ni español ni " +
                "inglés (ver PLAY_IN_ORDER_CHIP)"
            return false
        }
        chip.click()
        if (!device.wait(Until.hasObject(PLAYER_CLOSE_BUTTON), PLAYER_TIMEOUT_MS)) {
            lastStop = "el chip se pulsó pero el reproductor no apareció en ${PLAYER_TIMEOUT_MS} ms"
            return false
        }
        device.waitForIdle()

        device.pressBack()
        if (!device.wait(Until.hasObject(MINI_PLAYER), PLAYER_TIMEOUT_MS)) {
            lastStop = "el reproductor se abrió pero \"atrás\" no lo cerró a la píldora"
            return false
        }
        device.waitForIdle()

        // Segunda vuelta: ahora el morph completo desde la barra.
        val pill = device.findObject(MINI_PLAYER) ?: run {
            lastStop = "la píldora desapareció entre la espera y el click"
            return false
        }
        pill.click()
        if (!device.wait(Until.hasObject(PLAYER_CLOSE_BUTTON), PLAYER_TIMEOUT_MS)) {
            lastStop = "la píldora se pulsó pero el reproductor no volvió a abrirse"
            return false
        }
        device.waitForIdle()

        device.pressBack()
        device.wait(Until.hasObject(MINI_PLAYER), PLAYER_TIMEOUT_MS)
        device.waitForIdle()
        return true
    }

    /**
     * Cambio de pestaña: recorre el `HorizontalPager` y con él la composición de Artistas/Álbumes,
     * que son las pantallas con agregaciones y carga de imágenes.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.switchTabs() {
        // Sin `findObject`: los swipes van por coordenadas (`device.swipe`), que no pasa por ningún
        // `UiObject2`, así que buscar el pager solo servía para un `setGestureMargin` que este
        // camino ignora — y para heredar su `StaleObjectException`.
        repeat(2) {
            device.swipe(
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                device.displayWidth / 5,
                device.displayHeight / 2,
                SWIPE_STEPS
            )
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.qhana.siku"
        const val CONTENT_TIMEOUT_MS = 15_000L
        /**
         * Espera de las transiciones del reproductor. Holgada a propósito: no estima nada (el morph
         * dura ~330 ms), solo cubre un device cargado durante el arranque, donde poner la primera
         * canción puede tardar bastante más que el gesto.
         */
        const val PLAYER_TIMEOUT_MS = 10_000L
        /** Margen de borde para que el gesto no lo capture el "atrás" del sistema. */
        const val GESTURE_MARGIN_DIVISOR = 5
        const val SCROLL_PERCENT = 0.8f
        const val SWIPE_STEPS = 10
        /** Reintentos de un gesto cuyo nodo se recreó a mitad. Ver [scrollMainList]. */
        const val STALE_RETRIES = 3
        /** Pasadas de scroll hacia arriba para volver al principio del Inicio. Ver [exercisePlayer]. */
        const val SCROLL_TO_TOP_PASSES = 4
        /** Deslizamientos para volver a la PRIMERA pestaña: uno más que las cinco que hay. */
        const val TABS_TO_FIRST_PASSES = 6

        // Anclas del recorrido del reproductor, en los dos idiomas que tiene la app (`values` y
        // `values-en`). Son los literales de `home_action_play_order`, `np_close_desc` y
        // `mini_player_desc`: si alguno cambia, este recorrido deja de encontrar su objetivo y el
        // `check` de [generate] lo dice en voz alta en vez de producir un perfil incompleto.
        val PLAY_IN_ORDER_CHIP: Pattern = Pattern.compile("En orden|In order")
        val PLAYER_CLOSE_BUTTON = By.desc(Pattern.compile("Cerrar reproductor|Close player"))
        val MINI_PLAYER = By.desc(Pattern.compile("(Mini reproductor|Mini player): .*"))
    }
}
