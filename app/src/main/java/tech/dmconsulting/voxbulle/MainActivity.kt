package tech.dmconsulting.voxbulle

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.ui.navigation.VoxbulleNavHost
import tech.dmconsulting.voxbulle.ui.theme.VoxbulleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Test hook kept from the PoC (scripts/emu-proof.sh): start the bubble
        // from the foreground activity. Onboarding (DMC-140) brings the real path.
        if (intent.getBooleanExtra("showBubble", false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, BubbleService::class.java))
        }
        setContent {
            VoxbulleTheme {
                VoxbulleNavHost()
            }
        }
    }
}
