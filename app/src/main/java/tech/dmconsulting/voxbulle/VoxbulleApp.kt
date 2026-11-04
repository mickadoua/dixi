package tech.dmconsulting.voxbulle

import android.app.Application

class VoxbulleApp : Application() {
    val container: AppContainer by lazy { AppContainer(BuildConfig.MISTRAL_API_KEY) }
}
