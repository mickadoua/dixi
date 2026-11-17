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
            // noBackupFilesDir and res/xml backup rules: the keys never leave the phone.
            mistralKey = FileMistralKeyStore(
                dir = File(noBackupFilesDir, MISTRAL_KEYS_DIR),
                cipher = KeystoreKeyCipher(),
                legacyFile = File(noBackupFilesDir, LEGACY_MISTRAL_KEY_FILE),
                legacyName = getString(R.string.key_default_name, 1),
            ),
            history = DataStoreDictationHistory.create(dataStoreFile("dictation_history.json")),
            settings = DataStoreSettingsStore.create(dataStoreFile("settings.preferences_pb")),
            demoMode = BuildConfig.DEMO_MODE,
            demoRawText = { getString(R.string.demo_raw_text) },
        )
    }

    val recorder: Recorder by lazy { Recorder(this) }

    companion object {
        /** The saved keys (DMC-766). Under no_backup/, excluded in res/xml/data_extraction_rules.xml and backup_rules.xml. */
        const val MISTRAL_KEYS_DIR = "mistral_keys"

        /** The single key before DMC-766, moved into [MISTRAL_KEYS_DIR] at the first start. */
        const val LEGACY_MISTRAL_KEY_FILE = "mistral_key.bin"
    }
}
