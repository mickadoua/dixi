plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.play.publisher) apply false
    alias(libs.plugins.spotless)
}

// Style Kotlin (ktlint) : `sh ./gradlew spotlessCheck` vérifie, `sh ./gradlew
// spotlessApply` corrige. Les règles sont dans .editorconfig. Avec
// -PratchetFrom=<commit>, seuls les fichiers modifiés depuis ce commit sont
// vérifiés. Même configuration que mickadoua/template-android.
spotless {
    providers.gradleProperty("ratchetFrom").orNull?.let { ratchetFrom(it) }
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
