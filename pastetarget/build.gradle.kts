plugins {
    alias(libs.plugins.android.application)
}

// Emulator test app for auto-paste (DMC-436): a text field and a password field in another
// app's window. Dixi never pastes into its own package, hence a separate APK. Debug only: no
// release variant, so it is never in a release build nor sent to Play.
android {
    namespace = "tech.dmconsulting.dixi.pastetarget"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tech.dmconsulting.dixi.pastetarget"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
