import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.play.publisher)
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
    "UPLOAD_KEYSTORE_PATH",
    "UPLOAD_KEYSTORE_PASSWORD",
    "UPLOAD_KEY_ALIAS",
    "UPLOAD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.ifEmpty { null } }
val uploadKeyMissing = uploadKeyEnv.filterValues { it == null }.keys
if (uploadKeyMissing.isNotEmpty() && uploadKeyMissing.size < uploadKeyEnv.size) {
    throw GradleException("Upload key: missing $uploadKeyMissing (set all four UPLOAD_* or none)")
}

// Play service account key, as a file path (scripts/play-publish.sh writes it to a temp file).
// Not set: Gradle Play Publisher stays off, so ordinary builds never call Play.
val playCredentials = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_FILE").orNull?.ifEmpty { null }

android {
    // The app is Dixi since 06/11/2026 (DMC-383); the namespace and Kotlin packages follow
    // since 16/11/2026 (DMC-653).
    namespace = "tech.dmconsulting.dixi"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tech.dmconsulting.dixi"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // Auto-paste (accessibility service) ships in debug only until Play accepts it (v1.1,
        // DMC-572): its service sits in src/debug/AndroidManifest.xml, and this flag hides the
        // screens that lead to it everywhere else (release, probe).
        buildConfigField("boolean", "AUTO_PASTE", "false")
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
            buildConfigField("boolean", "AUTO_PASTE", "true")
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

    // Default language English (res/values), French in res/values-fr (DMC-463): a phone in
    // any other language sees English, like the en-US store listing. res/resources.properties
    // names English for the unqualified folder, so Android 13+ lists both in the per-app
    // language setting.
    androidResources {
        generateLocaleConfig = true
    }

    // Every visible text comes from resources, translated in every language.
    lint {
        error += setOf("MissingTranslation", "HardcodedText")
    }

    // Only the release variant goes to Play (never debug or probe).
    playConfigs {
        register("release") {
            enabled.set(playCredentials != null)
        }
    }
}

// Defaults for scripts/play-publish.sh, which passes --track and --release-status.
// AUTO: if the git versionCode is already taken on Play, use the next free one, so main
// (squashed, fewer commits than poc) can still publish after a poc upload.
play {
    enabled.set(false)
    playCredentials?.let { serviceAccountCredentials.set(file(it)) }
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(ReleaseStatus.DRAFT)
    resolutionStrategy.set(ResolutionStrategy.AUTO)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)
    // Already in the APK through navigation-compose (2.11.0): declared because the onboarding uses them directly.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
