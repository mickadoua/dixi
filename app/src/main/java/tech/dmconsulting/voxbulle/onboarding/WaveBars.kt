package tech.dmconsulting.voxbulle.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Low wave of the bubble at rest (charter § 6). */
private val RestBars = listOf(6f, 12f, 18f, 12f, 6f)

/** Five rounded vertical bars, centred: the sound wave of the bubble. */
@Composable
private fun WaveBars(heights: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 28f
        val width = 3f * unit
        heights.forEachIndexed { i, h ->
            val height = h * unit
            drawRoundRect(
                color = color,
                topLeft = Offset((3f + 5f * i) * unit, (size.height - height) / 2f),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2f),
            )
        }
    }
}

/** The bubble at rest, 56 dp, for the preview of screen 4. */
@Composable
fun BubblePreview(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .shadow(6.dp, CircleShape)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(11.dp),
    ) {
        WaveBars(RestBars, MaterialTheme.colorScheme.onPrimary, Modifier.size(34.dp))
    }
}
