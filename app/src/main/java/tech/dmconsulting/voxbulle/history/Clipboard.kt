package tech.dmconsulting.voxbulle.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Copies [text] and tells whether the app should confirm it itself:
 * from Android 13 the system already shows its own clipboard confirmation.
 */
fun copyToClipboard(context: Context, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Dixi", text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}
