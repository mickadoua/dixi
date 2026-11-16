package tech.dmconsulting.dixi

import android.app.Application
import androidx.datastore.dataStoreFile
import tech.dmconsulting.dixi.history.DataStoreDictationHistory
import tech.dmconsulting.dixi.recording.Recorder
import tech.dmconsulting.dixi.settings.DataStoreSettingsStore
import tech.dmconsulting.dixi.settings.FileMistralKeyStore
import tech.dmconsulting.dixi.settings.KeystoreKeyCipher
import java.io.File

class DixiApp : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            // noBackupFilesDir and res/xml backup rules: the key never leaves the phone.
            mistralKey = FileMistralKeyStore(File(noBackupFilesDir, MISTRAL_KEY_FILE), KeystoreKeyCipher()),
            history = DataStoreDictationHistory.create(dataStoreFile("dictation_history.json")),
            settings = DataStoreSettingsStore.create(dataStoreFile("settings.preferences_pb")),
            demoRawText = { getString(R.string.demo_raw_text) },
        )
    }

    val recorder: Recorder by lazy { Recorder(this) }

    companion object {
        /** Also named in res/xml/data_extraction_rules.xml and backup_rules.xml. */
        const val MISTRAL_KEY_FILE = "mistral_key.bin"
    }
}
