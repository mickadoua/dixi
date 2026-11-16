package tech.dmconsulting.dixi.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.ui.components.DemoModeLabel
import tech.dmconsulting.dixi.ui.components.GlowLayer
import tech.dmconsulting.dixi.ui.components.LocalDemoMode
import tech.dmconsulting.dixi.ui.components.NeonButton
import tech.dmconsulting.dixi.ui.components.neonGlow
import tech.dmconsulting.dixi.ui.theme.Dimens
import tech.dmconsulting.dixi.ui.theme.Neon

/** Onboarding screen 1 (neon mockup 08, DMC-213). [step]: this screen's dot among [steps]. */
@Composable
fun WelcomeScreen(onStart: () -> Unit, step: Int = 0, steps: Int = 4) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // Orange radial light behind the logo (ellipse at 50 % 55 % of the top 420 dp).
                val center = Offset(size.width / 2f, 420.dp.toPx() * 0.55f)
                drawRect(
                    Brush.radialGradient(
                        0f to Neon.Orange.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.width * 0.65f,
                    ),
                )
            },
    ) {
        if (LocalDemoMode.current) DemoModeLabel(Modifier.align(Alignment.TopEnd).padding(8.dp))
        Column(Modifier.fillMaxWidth().padding(top = 92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppMark()
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 44.dp))
            Text(
                stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 36.dp, end = 36.dp),
            )
            Text(
                stringResource(R.string.welcome_no_account),
                style = MaterialTheme.typography.bodyLarge,
                color = Neon.TextMuted,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Dimens.ScreenMargin)
                .padding(bottom = 20.dp),
        ) {
            StepDots(step, steps, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
            NeonButton(stringResource(R.string.welcome_start), onClick = onStart)
        }
    }
}

/** The app mark: 112 dp rounded square, orange outline and glow, five glowing bars. Decoration. */
@Composable
private fun AppMark() {
    val shape = RoundedCornerShape(33.dp)
    Box(
        Modifier
            .size(112.dp)
            .neonGlow(Neon.Orange, shape, GlowLayer(18.dp, 0.7f), GlowLayer(48.dp, 0.35f))
            .background(Neon.Surface, shape)
            .border(2.dp, Neon.Orange, shape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(19, 33, 56, 33, 19).forEach { h ->
                Box(
                    Modifier
                        .size(width = 7.dp, height = h.dp)
                        .neonGlow(Neon.Orange, RoundedCornerShape(4.dp), GlowLayer(8.dp, 1f))
                        .background(Neon.Orange, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/** Progress dots: the current one is a 24 dp orange pill. Read as « Étape 1 sur 4 ». */
@Composable
private fun StepDots(step: Int, steps: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.welcome_step, step + 1, steps)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(steps) { i ->
            val current = i == step
            Box(
                Modifier
                    .size(width = if (current) 24.dp else 6.dp, height = 6.dp)
                    .then(if (current) Modifier.neonGlow(Neon.Orange, RoundedCornerShape(3.dp), GlowLayer(8.dp, 1f)) else Modifier)
                    .background(if (current) Neon.Orange else Neon.Outline, RoundedCornerShape(3.dp)),
            )
        }
    }
}
