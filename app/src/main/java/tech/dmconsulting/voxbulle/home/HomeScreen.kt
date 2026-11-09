package tech.dmconsulting.voxbulle.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.history.copyToClipboard
import tech.dmconsulting.voxbulle.history.dictationDateLabel
import tech.dmconsulting.voxbulle.settings.BubbleToggle
import tech.dmconsulting.voxbulle.ui.components.DemoModeLabel
import tech.dmconsulting.voxbulle.ui.components.GlowLayer
import tech.dmconsulting.voxbulle.ui.components.LocalDemoMode
import tech.dmconsulting.voxbulle.ui.components.NeonCard
import tech.dmconsulting.voxbulle.ui.components.NeonChip
import tech.dmconsulting.voxbulle.ui.components.NeonSwitch
import tech.dmconsulting.voxbulle.ui.components.NeonToggleCard
import tech.dmconsulting.voxbulle.ui.components.SectionLabel
import tech.dmconsulting.voxbulle.ui.components.neonGlow
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import tech.dmconsulting.voxbulle.ui.theme.Neon

/**
 * Main screen (neon mockup 05, DMC-213): bubble switch, the big « Touchez, parlez »
 * button and the last dictation. [bubble]: shared with the settings.
 * [onOpenOffer]: null while the offer screen is hidden.
 */
@Composable
fun HomeScreen(bubble: BubbleToggle, history: DictationHistory, onOpenOffer: (() -> Unit)?) {
    val context = LocalContext.current
    val entries by history.entries.collectAsState(initial = null)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenMargin),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
                modifier = Modifier.weight(1f),
            )
            if (LocalDemoMode.current) DemoModeLabel(Modifier.padding(end = 8.dp))
            if (onOpenOffer != null) {
                NeonChip(
                    stringResource(R.string.offer_free),
                    color = Neon.Orange,
                    textColor = Neon.OrangeText,
                    onClick = onOpenOffer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        BubbleCard(bubble)
        Spacer(Modifier.height(36.dp))
        DictateButton(onClick = { BubbleService.dictate(context) }, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(36.dp))
        Text(
            stringResource(R.string.home_dictate_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.home_dictate_body),
            style = MaterialTheme.typography.bodyLarge,
            color = Neon.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.home_last_dictation))
        Spacer(Modifier.height(8.dp))
        LastDictation(entries?.firstOrNull(), onCopy = {
            // From Android 13 the system confirms the copy itself.
            if (copyToClipboard(context, it.text)) Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
        })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BubbleCard(bubble: BubbleToggle) {
    val shown = bubble.shown ?: false
    NeonToggleCard(
        checked = shown,
        onCheckedChange = bubble.set,
        enabled = bubble.shown != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel(
                    stringResource(if (shown) R.string.home_bubble_on else R.string.home_bubble_off),
                    color = if (shown) Neon.Cyan else Neon.TextMuted,
                )
                Text(
                    stringResource(if (shown) R.string.home_bubble_on_body else R.string.home_bubble_off_body),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            NeonSwitch(checked = shown, onCheckedChange = null, enabled = bubble.shown != null)
        }
    }
}

/** 132 dp orange disc with a mic, its glow and two rings (14 and 30 dp out, 35 % and 15 %). */
@Composable
private fun DictateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(132.dp + 64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val disc = 66.dp.toPx()
            listOf(14.dp to 0.35f, 30.dp to 0.15f).forEach { (gap, alpha) ->
                drawCircle(Neon.Orange.copy(alpha = alpha), radius = disc + gap.toPx() - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Neon.Orange,
            contentColor = Neon.OnOrange,
            modifier = Modifier
                .size(132.dp)
                .neonGlow(Neon.Orange, CircleShape, GlowLayer(24.dp, 0.8f), GlowLayer(70.dp, 0.4f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_mic),
                    contentDescription = stringResource(R.string.home_dictate_action),
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

@Composable
private fun LastDictation(dictation: Dictation?, onCopy: (Dictation) -> Unit) {
    NeonCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Neon.Hairline)) {
        if (dictation == null) {
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = Neon.TextMuted)
            return@NeonCard
        }
        Text(dictation.text, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                dictationDateLabel(dictation.createdAt),
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.TextMuted,
                modifier = Modifier.weight(1f),
            )
            NeonChip(
                stringResource(R.string.history_copy_action),
                color = Neon.Cyan,
                height = 32.dp,
                onClick = { onCopy(dictation) },
            )
        }
    }
}
