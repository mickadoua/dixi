package tech.dmconsulting.voxbulle

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
            BubbleService.show(this)
        }
        setContent {
            VoxbulleTheme {
                VoxbulleNavHost()
            }
        }
    }
}
