package tech.dmconsulting.voxbulle.ui.navigation

import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R

/** App screens, numbered like the mockups (DMC-121). Screens 6, 7 and 11 are not app screens. */
enum class Screen(val number: Int, @StringRes val title: Int) {
    Welcome(1, R.string.screen_welcome),
    MicPermission(2, R.string.screen_mic_permission),
    Trial(3, R.string.screen_trial),
    BubbleIntro(4, R.string.screen_bubble_intro),
    BubbleEnabled(5, R.string.screen_bubble_enabled),
    AutoPasteDisclosure(8, R.string.screen_auto_paste_disclosure),
    History(9, R.string.screen_history),
    Settings(10, R.string.screen_settings),
    ;

    val route: String get() = name

    /** Screens reachable from this one, following the mockups' journey. */
    val links: List<Screen>
        get() = when (this) {
            Welcome -> listOf(MicPermission)
            MicPermission -> listOf(Trial)
            Trial -> listOf(BubbleIntro)
            BubbleIntro -> listOf(BubbleEnabled)
            BubbleEnabled -> listOf(History, AutoPasteDisclosure)
            AutoPasteDisclosure -> emptyList()
            History -> listOf(Settings)
            Settings -> listOf(AutoPasteDisclosure)
        }

    companion object {
        val start = Welcome
    }
}
