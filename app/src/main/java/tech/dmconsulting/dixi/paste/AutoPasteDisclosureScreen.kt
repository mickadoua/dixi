package tech.dmconsulting.dixi.paste

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
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.ui.components.DemoModeLabel
import tech.dmconsulting.dixi.ui.components.LocalDemoMode
import tech.dmconsulting.dixi.ui.components.NeonButton
import tech.dmconsulting.dixi.ui.components.NeonCard
import tech.dmconsulting.dixi.ui.components.SectionLabel
import tech.dmconsulting.dixi.ui.theme.Dimens
import tech.dmconsulting.dixi.ui.theme.Neon

/**
 * Screen 8 (mockup DMC-121): the prominent disclosure Google Play asks for, shown before
 * any trip to Android's accessibility settings. The text is the mockup's, a **draft to be
 * validated before publication** (founder). « Non merci » changes nothing.
 */
@Composable
fun AutoPasteDisclosureScreen(onBack: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit) {
    // Buttons pinned at the bottom, the text scrolls above them on small screens.
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenMargin)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
                    Text("‹  " + stringResource(R.string.screen_settings), style = MaterialTheme.typography.bodyLarge, color = Neon.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                if (LocalDemoMode.current) DemoModeLabel()
            }
            SectionLabel(stringResource(R.string.screen_auto_paste_disclosure), color = Neon.Cyan)
            Text(
                stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.disclosure_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            NeonCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(R.string.disclosure_point_no_reading, R.string.disclosure_point_no_data, R.string.disclosure_point_off_anytime)
                        .forEach { Text("•  " + stringResource(it), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            Text(
                stringResource(R.string.disclosure_optional),
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.TextMuted,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
        NeonButton(stringResource(R.string.disclosure_accept), onClick = onAccept)
        TextButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight).padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.disclosure_decline), style = MaterialTheme.typography.bodyLarge, color = Neon.Text)
        }
        Spacer(Modifier.height(24.dp))
    }
}
