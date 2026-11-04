package tech.dmconsulting.voxbulle.recording

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

/**
 * Puts [service] in the foreground with the microphone type, required to
 * record while another app is in front (Android 14+).
 */
object MicrophoneForeground {

    fun start(service: Service, id: Int, notification: Notification) {
        ServiceCompat.startForeground(
            service,
            id,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    fun stop(service: Service) = ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
}
