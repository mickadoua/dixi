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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Offer screen (neon mockup 07, DMC-213). Slots only: no price and no paid
 * feature until the founder defines the offers. The button does nothing yet.
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
                Slot(stringResource(R.string.offer_slot_free_features), Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            NeonCard(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.5.dp, Neon.Magenta),
                glow = Neon.Magenta to GlowLayer(22.dp, 0.28f),
            ) {
                OfferHeader(stringResource(R.string.offer_paid)) {
                    NeonChip(stringResource(R.string.offer_paid_name), color = Neon.Magenta)
                }
                Slot(stringResource(R.string.offer_slot_paid_features), Modifier.padding(top = 12.dp))
                Slot(stringResource(R.string.offer_slot_price), Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
        NeonButton(stringResource(R.string.offer_upgrade), onClick = {})
        Text(
            stringResource(R.string.offer_mockup_note),
            style = MaterialTheme.typography.bodyMedium,
            color = Neon.Magenta,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun OfferHeader(name: String, chip: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        chip()
    }
}

/** `[EMPLACEMENT …]`: dashed magenta frame, to be replaced when the offers are defined. */
@Composable
private fun Slot(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        color = Neon.Magenta,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = Neon.Magenta,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
