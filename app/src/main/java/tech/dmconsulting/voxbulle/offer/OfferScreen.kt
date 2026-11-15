package tech.dmconsulting.voxbulle.offer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.ui.components.DemoModeLabel
import tech.dmconsulting.voxbulle.ui.components.GlowLayer
import tech.dmconsulting.voxbulle.ui.components.LocalDemoMode
import tech.dmconsulting.voxbulle.ui.components.NeonButton
import tech.dmconsulting.voxbulle.ui.components.NeonCard
import tech.dmconsulting.voxbulle.ui.components.NeonChip
import tech.dmconsulting.voxbulle.ui.components.SectionLabel
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import tech.dmconsulting.voxbulle.ui.theme.Neon

/**
 * Offer screen (neon mockup 07, DMC-213). Frame only: no feature, name or price until the
 * founder defines the offers, and no placeholder text in the APK (DMC-572). The button does
 * nothing yet; the screen stays hidden (Screen.offerShown).
 */
@Composable
fun OfferScreen(onBack: () -> Unit) {
    // Button pinned at the bottom, the cards scroll above it on small screens.
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenMargin)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
                    Text("‹  " + stringResource(R.string.screen_settings), style = MaterialTheme.typography.bodyLarge, color = Neon.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                if (LocalDemoMode.current) DemoModeLabel()
            }
            SectionLabel(stringResource(R.string.offer_label), color = Neon.Magenta)
            Text(
                stringResource(R.string.offer_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
            NeonCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Neon.Outline)) {
                OfferHeader(stringResource(R.string.offer_free)) {
                    NeonChip(stringResource(R.string.offer_current), color = Neon.Orange, textColor = Neon.OrangeText)
                }
            }
            Spacer(Modifier.height(16.dp))
            NeonCard(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.5.dp, Neon.Magenta),
                glow = Neon.Magenta to GlowLayer(22.dp, 0.28f),
            ) {
                OfferHeader(stringResource(R.string.offer_paid)) {}
            }
            Spacer(Modifier.height(32.dp))
        }
        NeonButton(stringResource(R.string.offer_upgrade), onClick = {})
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OfferHeader(name: String, chip: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        chip()
    }
}
