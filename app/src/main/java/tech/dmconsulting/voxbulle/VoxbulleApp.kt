package tech.dmconsulting.voxbulle

import android.app.Application
import androidx.datastore.dataStoreFile
import java.io.File
import tech.dmconsulting.voxbulle.history.DataStoreDictationHistory
import tech.dmconsulting.voxbulle.recording.Recorder
import tech.dmconsulting.voxbulle.settings.DataStoreSettingsStore
import tech.dmconsulting.voxbulle.settings.FileMistralKeyStore
import tech.dmconsulting.voxbulle.settings.KeystoreKeyCipher

class VoxbulleApp : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            // noBackupFilesDir and res/xml backup rules: the key never leaves the phone.
            mistralKey = FileMistralKeyStore(File(noBackupFilesDir, MISTRAL_KEY_FILE), KeystoreKeyCipher()),
            history = DataStoreDictationHistory.create(dataStoreFile("dictation_history.json")),
            settings = DataStoreSettingsStore.create(dataStoreFile("settings.preferences_pb")),
        )
    }

    val recorder: Recorder by lazy { Recorder(this) }

    companion object {
        /** Also named in res/xml/data_extraction_rules.xml and backup_rules.xml. */
        const val MISTRAL_KEY_FILE = "mistral_key.bin"
    }
}
