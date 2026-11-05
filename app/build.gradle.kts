plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Release numbering. versionCode = number of commits on HEAD (grows with every merge on the
// release branch, same value for the same commit); VERSION_CODE overrides it, e.g. to upload
// the same commit twice. versionName = last tag vX.Y.Z without the "v"; VERSION_NAME overrides it.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val appVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt()
    ?: git("rev-list", "--count", "HEAD")?.toIntOrNull()
    ?: 1
val appVersionName = providers.environmentVariable("VERSION_NAME").orNull
    ?: git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*.[0-9]*.[0-9]*")?.removePrefix("v")
    ?: "0.1.0"

// Upload key for Play, read from the environment only. No variable set: release stays unsigned
// (dev machines, CI). Some but not all set: the build fails, so a half-configured release is
// never uploaded unsigned by mistake.
val uploadKeyEnv = listOf(
    "UPLOAD_KEYSTORE_PATH", "UPLOAD_KEYSTORE_PASSWORD", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.ifEmpty { null } }
val uploadKeyMissing = uploadKeyEnv.filterValues { it == null }.keys
if (uploadKeyMissing.isNotEmpty() && uploadKeyMissing.size < uploadKeyEnv.size) {
    throw GradleException("Upload key: missing $uploadKeyMissing (set all four UPLOAD_* or none)")
}

android {
    namespace = "tech.dmconsulting.voxbulle"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tech.dmconsulting.voxbulle"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    if (uploadKeyMissing.isEmpty()) {
        signingConfigs.create("release") {
            storeFile = file(uploadKeyEnv.getValue("UPLOAD_KEYSTORE_PATH")!!)
            storePassword = uploadKeyEnv.getValue("UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = uploadKeyEnv.getValue("UPLOAD_KEY_ALIAS")
            keyPassword = uploadKeyEnv.getValue("UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        // Shrunk so the debug APK fits the 10 MB task attachment limit. The real Mistral
        // path under R8 is checked on a device with the probe (scripts/mistral-probe.sh),
        // in this build and in the non-debuggable probe build below.
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release as built by R8 (not debuggable, full optimisation) plus the Mistral probe,
        // signed with the debug key so it installs on the emulator. Never distributed.
        create("probe") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The probe (src/probe) also runs in debug builds and in its JVM test (testDebug).
    sourceSets.getByName("debug") {
        kotlin.srcDir("src/probe/java")
        res.srcDir("src/probe/res")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
