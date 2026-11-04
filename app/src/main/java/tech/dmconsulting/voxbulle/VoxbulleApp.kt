package tech.dmconsulting.voxbulle

import android.app.Application
import tech.dmconsulting.voxbulle.recording.Recorder

class VoxbulleApp : Application() {
    val container: AppContainer by lazy { AppContainer(BuildConfig.MISTRAL_API_KEY, fakesAllowed = BuildConfig.DEBUG) }

    val recorder: Recorder by lazy { Recorder(this) }
}
