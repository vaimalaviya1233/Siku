// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.1" apply false
    // Kotlin SE QUEDA en 2.3.0 hasta que KSP publique para 2.4. MaterialKolor 5.0.0 exige
    // `kotlin-stdlib:2.4.0` (lo declara su .module) y trae metadata de ese compilador, así que
    // adoptarla obliga a subir Kotlin — pero la última KSP publicada es **2.3.9** (verificado en
    // Maven Central el 28 jul 2026), y sin KSP no compilan ni Room ni Hilt. El bloqueo es ese, no
    // el compileSdk. Cuando salga KSP 2.4.x: subir estos dos, KSP, y ahí sí MaterialKolor 5.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.2" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
}
