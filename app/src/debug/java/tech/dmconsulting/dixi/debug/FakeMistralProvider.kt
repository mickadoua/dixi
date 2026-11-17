package tech.dmconsulting.dixi.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import tech.dmconsulting.dixi.transcription.MistralHttp
import java.io.File
import java.util.Locale

/**
 * Debug builds only: starts [FakeMistralServer] before the app container exists (a provider
 * runs before Application.onCreate), when a proof script created `files/fake-mistral`:
 *
 *   adb shell run-as tech.dmconsulting.dixi sh -c 'echo ok > files/fake-mistral'   # canned answers
 *   adb shell run-as tech.dmconsulting.dixi sh -c 'echo 401 > files/fake-mistral'  # dictations refused
 *
 * Without the file, the debug build talks to the real Mistral, as before. The file is read on
 * each request; switching it on or off needs a restart of the app process.
 */
class FakeMistralProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val flag = File(requireNotNull(context) { "provider without context" }.filesDir, FLAG_FILE)
        if (flag.exists()) {
            val server = FakeMistralServer(
                mode = { runCatching { flag.readText() }.getOrNull() },
                french = { Locale.getDefault().language == "fr" },
            ).start()
            MistralHttp.debugBaseUrl = server.baseUrl
            Log.i(TAG, "fake mistral on ${server.baseUrl}")
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val FLAG_FILE = "fake-mistral"
        const val TAG = "DixiProof"
    }
}
