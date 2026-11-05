package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Charter § 4. M3 buttons are already pills (full radius, 28 dp at 56 dp high).
val VoxbulleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    // Cards (M3 cards use the medium shape).
    medium = RoundedCornerShape(16.dp),
    // Icon chips, 64 dp.
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
