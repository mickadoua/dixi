package tech.dmconsulting.voxbulle.bubble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Shows or hides the bubble from the app (settings, screen 10). */
object BubbleSwitch {
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        context.startService(Intent(context, BubbleService::class.java))
    }

    fun hide(context: Context) {
        context.stopService(Intent(context, BubbleService::class.java))
    }

    /** Android's "Display over other apps" page for this app. */
    fun overlayPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
