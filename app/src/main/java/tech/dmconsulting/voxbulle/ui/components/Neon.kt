package tech.dmconsulting.voxbulle.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import tech.dmconsulting.voxbulle.ui.theme.Neon

/** One glow layer: CSS `0 0 <blur> <color @ alpha>` of the neon charter. */
data class GlowLayer(val blur: Dp, val alpha: Float)

/**
 * Neon glow outside [shape], drawn in layers (BlurMaskFilter, Android 9+).
 * Before Android 9 the blur is ignored by the GPU canvas: nothing is drawn,
 * the element keeps its outline only (charter fallback, same meaning).
 */
fun Modifier.neonGlow(color: Color, shape: Shape, vararg layers: GlowLayer): Modifier = drawBehind {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    drawIntoCanvas { canvas ->
        layers.forEach { layer ->
            // CSS blur = 2 sigma; Android: sigma = 0.57735 radius + 0.5.
            val radius = ((layer.blur.toPx() / 2f - 0.5f) / 0.57735f).coerceAtLeast(1f)
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER)
            paint.color = color.copy(alpha = layer.alpha).toArgb()
            when (outline) {
                is Outline.Rectangle -> canvas.nativeCanvas.drawRect(
                    outline.rect.left,
                    outline.rect.top,
                    outline.rect.right,
                    outline.rect.bottom,
                    paint,
                )

                is Outline.Rounded -> {
                    val r = outline.roundRect
                    canvas.nativeCanvas.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
                }

                is Outline.Generic -> canvas.nativeCanvas.drawPath(outline.path.asAndroidPath(), paint)
            }
        }
    }
}

private val CardShape = RoundedCornerShape(16.dp)
val PillShape = CircleShape

/** Card: surface, hairline border. [important]: orange border @ 55 % and a 22 dp glow @ 18 %. */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    important: Boolean = false,
    border: BorderStroke = if (important) BorderStroke(1.dp, Neon.Orange.copy(alpha = 0.55f)) else BorderStroke(1.dp, Neon.Hairline),
    glow: Pair<Color, GlowLayer>? = if (important) Neon.Orange to GlowLayer(22.dp, 0.18f) else null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.cardGlow(glow),
        shape = CardShape,
        color = Neon.Surface,
        contentColor = Neon.Text,
        border = border,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/**
 * The whole card is a switch: one accessibility node, a large target. The
 * press ripple is clipped to the 16 dp corners, like the glow.
 */
@Composable
fun NeonToggleCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { role = Role.Switch }.cardGlow(Neon.Orange to GlowLayer(22.dp, 0.18f)),
        enabled = enabled,
        shape = CardShape,
        color = Neon.Surface,
        contentColor = Neon.Text,
        border = BorderStroke(1.dp, Neon.Orange.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private fun Modifier.cardGlow(glow: Pair<Color, GlowLayer>?): Modifier = glow?.let { (color, layer) -> neonGlow(color, CardShape, layer) } ?: this

/** Filled orange pill, 56 dp: the main action. */
@Composable
fun NeonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ButtonHeight)
            .neonGlow(Neon.Orange, PillShape, GlowLayer(14.dp, 0.55f), GlowLayer(36.dp, 0.3f)),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Neon.Orange, contentColor = Neon.OnOrange),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
        trailing()
    }
}

/**
 * Outlined pill, 56 dp. [color]: the outline; [textColor]: its text (orange text
 * is lighter than the orange line, charter). [glow]: 12 dp @ 35 % of the outline color.
 */
@Composable
fun NeonOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Neon.Outline,
    textColor: Color = Neon.Text,
    glow: Boolean = false,
) {
    val glowModifier = if (glow) Modifier.neonGlow(color, PillShape, GlowLayer(12.dp, 0.35f)) else Modifier
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.ButtonHeight).then(glowModifier),
        shape = PillShape,
        border = BorderStroke(1.5.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Outlined pill label, 28 dp high (32 dp for an action), 14 sp. Clickable ones get a 48 dp target. */
@Composable
fun NeonChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = color,
    height: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
) {
    val inner = @Composable {
        Row(
            Modifier
                .height(height)
                .border(1.5.dp, color, PillShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = 1)
        }
    }
    if (onClick == null) {
        Surface(modifier = modifier, shape = PillShape, color = Color.Transparent) { inner() }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.minimumInteractiveComponentSize(),
            shape = PillShape,
            color = Color.Transparent,
        ) { inner() }
    }
}

/** Section label: 12 sp caps, decoration only (never the only carrier of information). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Neon.TextMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** The 52 × 32 dp track, centered in the switch's 48 dp touch box. */
private object SwitchTrackShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = with(density) { 52.dp.toPx() }
        val h = with(density) { 32.dp.toPx() }
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        return Outline.Rounded(RoundRect(left, top, left + w, top + h, CornerRadius(h / 2f)))
    }
}

/** Material switch in the neon colors; glows orange when on. [onCheckedChange] null: the row toggles. */
@Composable
fun NeonSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val glow = if (checked && enabled) Modifier.neonGlow(Neon.Orange, SwitchTrackShape, GlowLayer(12.dp, 0.6f)) else Modifier
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = glow,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Neon.OnOrange,
            checkedTrackColor = Neon.Orange,
            checkedBorderColor = Neon.Orange,
            uncheckedThumbColor = Neon.Outline,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = Neon.Outline,
            disabledUncheckedThumbColor = Neon.Outline.copy(alpha = 0.38f),
            disabledUncheckedBorderColor = Neon.Outline.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = Color.Transparent,
        ),
    )
}
