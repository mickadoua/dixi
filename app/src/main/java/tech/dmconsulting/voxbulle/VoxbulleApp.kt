package tech.dmconsulting.voxbulle

import android.app.Application
import androidx.datastore.dataStoreFile
import tech.dmconsulting.voxbulle.history.DataStoreDictationHistory
import tech.dmconsulting.voxbulle.settings.DataStoreSettingsStore

class VoxbulleApp : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            mistralApiKey = BuildConfig.MISTRAL_API_KEY,
            fakesAllowed = BuildConfig.DEBUG,
            history = DataStoreDictationHistory.create(dataStoreFile("dictation_history.json")),
            settings = DataStoreSettingsStore.create(dataStoreFile("settings.preferences_pb")),
        )
    }
}
