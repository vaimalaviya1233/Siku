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
 * Uso:  ./gradlew :baselineprofile:generateBaselineProfile
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
     * Al generar se instala `nonMinifiedRelease`: mismo applicationId que la release y la MISMA
     * firma (hereda su signingConfig), así que entra como actualización y conserva los datos. Al
     * terminar hay que reinstalar la release, que es la que lleva R8. **Requisito**: que exista
     * `keystore.properties`; sin él la release cae a la firma debug (ver el fallback en
     * `app/build.gradle.kts`) y ahí sí haría falta desinstalar, con la pérdida de biblioteca y
     * listas que implica `allowBackup=false`.
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
