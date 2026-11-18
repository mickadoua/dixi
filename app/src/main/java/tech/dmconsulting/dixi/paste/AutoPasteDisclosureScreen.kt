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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * Screen 8: the prominent disclosure Google Play asks for, shown before any trip to Android's
 * accessibility settings, with a real choice (« Not now » leaves the app usable). Final texts
 * by Léa (DMC-765), checked against [AutoPasteService]: change them if the service changes.
 * After « Agree and turn on », one line says what Android will show, then its settings open.
 */
@Composable
fun AutoPasteDisclosureScreen(onBack: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    var nextStep by rememberSaveable { mutableStateOf(false) }
    // Buttons pinned at the bottom, the text scrolls above them on small screens.
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenMargin)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
                    Text("‹  " + stringResource(R.string.action_back), style = MaterialTheme.typography.bodyLarge, color = Neon.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                if (LocalDemoMode.current) DemoModeLabel()
            }
            Text(
                stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.disclosure_intro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            NeonCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(
                        R.string.disclosure_reads_title to R.string.disclosure_reads,
                        R.string.disclosure_does_title to R.string.disclosure_does,
                        R.string.disclosure_does_not_title to R.string.disclosure_does_not,
                        R.string.disclosure_stop_title to R.string.disclosure_stop,
                    ).forEach { (title, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionLabel(stringResource(title), color = Neon.Cyan)
                            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            listOf(R.string.disclosure_voice_note, R.string.disclosure_optional).forEach {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neon.TextMuted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
        NeonButton(stringResource(R.string.disclosure_accept), onClick = {
            if (AutoPasteService.isEnabled(context)) onAccept() else nextStep = true
        })
        TextButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight).padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.disclosure_decline), style = MaterialTheme.typography.bodyLarge, color = Neon.Text)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (nextStep) {
        AlertDialog(
            onDismissRequest = { nextStep = false },
            text = { Text(stringResource(R.string.disclosure_next_step)) },
            confirmButton = {
                TextButton(onClick = {
                    nextStep = false
                    onAccept()
                }) { Text(stringResource(R.string.key_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { nextStep = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
