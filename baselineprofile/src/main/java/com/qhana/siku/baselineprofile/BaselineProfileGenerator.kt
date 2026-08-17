package com.qhana.siku.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Graba el baseline profile de Siku Music.
 *
 * QUÉ es: la lista de clases y métodos que Android debe compilar AOT al instalar la app, en vez de
 * interpretarlos y JITearlos durante los primeros arranques. Las librerías de Compose ya traen el
 * suyo, pero el código propio —composables, ViewModels, el DAO generado por Room— no tenía
 * ninguno, así que el primer arranque y el primer scroll de cada instalación se pagaban enteros.
 *
 * CÓMO se usa: `./gradlew :baselineprofile:generateBaselineProfile`. El resultado se escribe solo
 * en `app/src/.../generated/baselineProfiles/` y **hay que commitearlo**: es un artefacto de build
 * reproducible, no un archivo temporal.
 *
 * CUÁNDO hay que regenerarlo: cuando el recorrido crítico cambie de forma (una pantalla nueva en
 * el arranque, un cambio grande de navegación). No hace falta por cada commit — un perfil algo
 * viejo sigue siendo mucho mejor que ninguno, porque lo que perfila son los caminos, no las líneas.
 *
 * El recorrido de abajo es deliberadamente el del USUARIO REAL en sus primeros segundos, no una
 * demo de todas las pantallas: arrancar, ver la biblioteca, scrollear, cambiar de pestaña. Meter
 * de todo dilata el perfil y diluye lo que de verdad importa optimizar.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        // Varias iteraciones: el perfil se queda con lo que se repite, así que una pasada suelta
        // arrastraría ruido de esa ejecución concreta.
        maxIterations = 8,
        stableIterations = 3,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()

        // La biblioteca puede tardar en poblarse (escaneo de arranque). Se espera por CONTENIDO y
        // no con un sleep fijo: en un emulador frío el margen real varía mucho.
        device.wait(Until.hasObject(By.scrollable(true)), CONTENT_TIMEOUT_MS)

        scrollLibrary()
        switchTabs()
    }

    /** Scroll de la lista principal: es donde se nota el jank sin perfil. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollLibrary() {
        val list = device.findObject(By.scrollable(true)) ?: return
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(3) {
            list.scroll(Direction.DOWN, SCROLL_PERCENT)
            device.waitForIdle()
        }
        list.scroll(Direction.UP, 1f)
        device.waitForIdle()
    }

    /**
     * Cambio de pestaña: recorre el `HorizontalPager` y con él la composición de Artistas/Álbumes,
     * que son las pantallas con agregaciones y carga de imágenes.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.switchTabs() {
        val pager = device.findObject(By.scrollable(true)) ?: return
        pager.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
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
        /** Margen de borde para que el gesto no lo capture el "atrás" del sistema. */
        const val GESTURE_MARGIN_DIVISOR = 5
        const val SCROLL_PERCENT = 0.8f
        const val SWIPE_STEPS = 10
    }
}
