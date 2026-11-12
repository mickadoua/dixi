package tech.dmconsulting.voxbulle.ui.navigation

import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R

/**
 * App screens, numbered like the mockups (DMC-121). Screens 6, 7 and 11 are not app
 * screens. Home and Offer come from the neon mockups 05 and 07 (DMC-213): 12 and 13.
 */
enum class Screen(val number: Int, @StringRes val title: Int) {
    Welcome(1, R.string.screen_welcome),
    MicPermission(2, R.string.screen_mic_permission),
    Trial(3, R.string.screen_trial),
    BubbleIntro(4, R.string.screen_bubble_intro),
    BubbleEnabled(5, R.string.screen_bubble_enabled),
    AutoPasteDisclosure(8, R.string.screen_auto_paste_disclosure),
    History(9, R.string.screen_history),
    Settings(10, R.string.screen_settings),
    Home(12, R.string.screen_home),
    Offer(13, R.string.screen_offer),
    ;

    val route: String get() = name

    /** Screens reachable from this one, following the mockups' journey. */
    val links: List<Screen>
        get() = when (this) {
            Welcome -> listOf(MicPermission)
            MicPermission -> listOf(Trial, BubbleIntro)
            Trial -> listOf(BubbleIntro)
            BubbleIntro -> listOf(BubbleEnabled)
            BubbleEnabled -> listOf(Home, AutoPasteDisclosure)
            AutoPasteDisclosure -> emptyList()
            History -> listOf(Settings)
            Settings -> listOf(Offer, AutoPasteDisclosure)
            Home -> listOf(Offer)
            Offer -> emptyList()
        }

    /** Screens of the bottom tab bar, in order. */
    val isTab: Boolean get() = this in tabs

    companion object {
        /** First screen of the onboarding. */
        val start = Welcome
        val tabs = listOf(Home, History, Settings)

        /**
         * The offer screen and its ways in (main screen chip, « Votre offre » card) stay
         * hidden until the founder sets the offers: no placeholder text in the store build (DMC-338).
         */
        val offerShown = false

        /** Where the app opens once the onboarding is done. */
        val home = Home
    }
}
