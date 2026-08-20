plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

/**
 * Módulo SOLO de generación: no se empaqueta con la app ni entra en ninguna variante suya.
 * Corre un recorrido real sobre `:app` en un device/emulador y escribe el perfil resultante en
 * `app/src/<variante>/generated/baselineProfiles/`, que AGP mete en el APK.
 *
 * Uso:  ./gradlew :app:generateBaselineProfile
 *
 * **La tarea es de `:app` y NO de este módulo, aunque el recorrido viva aquí.** El plugin
 * `androidx.baselineprofile` reparte dos papeles: aquí es el PRODUCTOR (aporta el test y las
 * variantes `nonMinified*`) y en `:app` es el CONSUMIDOR (`baselineProfile(project(":baselineprofile"))`),
 * y es el consumidor quien registra `generateBaselineProfile` — porque es él quien decide para qué
 * variante se genera y dónde se escribe el resultado. Pedirla aquí falla con "task not found in
 * project ':baselineprofile'". Variante concreta: `:app:generateReleaseBaselineProfile`.
 */
android {
    namespace = "com.qhana.siku.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        // 28 es el mínimo del generador (necesita `profileable`), aunque la app soporte 26: el
        // perfil se GENERA en un device moderno y luego SIRVE también a los de API 26-27, que lo
        // aplican al instalar. No hay que bajar esto para cubrirlos.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

// Emulador gestionado, disponible como alternativa si no hay teléfono a mano. NO es el camino por
// defecto: ver el porqué en `baselineProfile { }`.
android {
    @Suppress("UnstableApiUsage")
    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp"
    }
}

baselineProfile {
    /*
     * Contra el DISPOSITIVO CONECTADO, y en concreto contra uno que tenga música dentro.
     *
     * El emulador gestionado se descartó como default por un motivo de contenido, no de fidelidad:
     * arranca con la app recién instalada y sin biblioteca, así que se queda en el onboarding y el
     * recorrido del generador —scroll de la lista y cambio de pestaña— no encuentra nada que
     * recorrer. El perfil saldría cubriendo justo lo que no importa.
     *
     * ⚠️ **GENERAR EL PERFIL BORRA LA APP Y SUS DATOS.** Es un test instrumentado, y AGP DESINSTALA
     * la app al terminar: es el comportamiento estándar de `connectedAndroidTest` y **no depende de
     * la firma ni del applicationId** — la INSTALACIÓN sí entra como actualización (mismo
     * applicationId, misma firma heredada del signingConfig, sin `applicationIdSuffix`), pero eso no
     * dice nada de la desinstalación del final. Este bloque afirmó lo contrario hasta el 17 ago 2026
     * («entra como actualización y conserva los datos»), y esa frase fue la que llevó a correrlo sin
     * exportar nada antes: se perdió la biblioteca del usuario. COMPROBADO.
     *
     * Con `allowBackup=false`, lo que se va: biblioteca, descargas, playlists, favoritos, playCount,
     * colores manuales y perfiles del EQ. **ANTES de generar, exportar las playlists a OneDrive**
     * (`PlaylistBackupRepository.export`, que incluye favoritos) — es lo ÚNICO que tiene copia; la
     * biblioteca y las descargas se rehacen solas y lo demás no vuelve.
     *
     * Para no perder nada: generarlo en OTRO device o en el emulador gestionado, con música dentro
     * (ver el gotcha de `is_pending` en CLAUDE.md). El perfil no depende del device donde se grabó.
     *
     * Al terminar hay que reinstalar la release, que es la que lleva R8 y la única que aplica el
     * perfil. `keystore.properties` sigue haciendo falta, pero para que la release quede firmada de
     * verdad.
     *
     * Para usar el emulador en su lugar: cambiar a `useConnectedDevices = false` y descomentar
     * `managedDevices`, asumiendo que el perfil solo cubrirá el arranque.
     */
    useConnectedDevices = true
    // managedDevices += "pixel6Api34"
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
