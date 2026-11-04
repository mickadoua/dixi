package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGeometryTest {

    /** Phone of [widthDp] at [density], 56 dp bubble, 8 dp margin, status 24 dp, nav 48 dp. */
    private fun phone(widthDp: Int, density: Float = 2.625f, heightDp: Int = 720): BubbleGeometry {
        fun px(dp: Int) = (dp * density).toInt()
        return BubbleGeometry(
            screenWidth = px(widthDp),
            screenHeight = px(heightDp),
            bubbleSize = px(56),
            margin = px(8),
            insets = BubbleGeometry.Insets(0, px(24), 0, px(48)),
        )
    }

    @Test
    fun snapsToTheNearestSideAt8dpOnScreensFrom320To480dp() {
        for (widthDp in listOf(320, 360, 393, 412, 480)) {
            for (density in listOf(1f, 2f, 2.625f, 3.5f)) {
                val g = phone(widthDp, density)
                val px = { dp: Int -> (dp * density).toInt() }
                val size = px(56)
                val width = px(widthDp)

                val left = g.snap(x = px(30), y = px(300))
                assertEquals("$widthDp dp @ $density", Side.Left, left.side)
                assertEquals(px(8), g.place(left).first)

                val right = g.snap(x = width - size - px(30), y = px(300))
                assertEquals("$widthDp dp @ $density", Side.Right, right.side)
                assertEquals(width - px(8) - size, g.place(right).first)
            }
        }
    }

    @Test
    fun centerDecidesTheSide() {
        val g = phone(360, density = 1f)
        // Bubble center exactly on the middle line goes left; one pixel past it goes right.
        assertEquals(Side.Left, g.nearestSide(180 - 28))
        assertEquals(Side.Right, g.nearestSide(180 - 28 + 1))
    }

    @Test
    fun dropPastTheEdgesStillLandsInside() {
        val g = phone(360, density = 1f)
        assertEquals(Side.Left, g.snap(x = -100, y = 300).side)
        assertEquals(Side.Right, g.snap(x = 1_000, y = 300).side)
        val top = g.snap(x = 0, y = -50)
        assertEquals(0f, top.yFraction)
        assertEquals(24 + 8, g.place(top).second)
        val bottom = g.snap(x = 0, y = 5_000)
        assertEquals(1f, bottom.yFraction)
        assertEquals(720 - 48 - 8 - 56, g.place(bottom).second)
    }

    @Test
    fun savedPlaceComesBackOnTheSameScreen() {
        val g = phone(412)
        val saved = g.snap(x = 900, y = 1_000)
        assertEquals(g.clampY(1_000), g.place(saved).second)
    }

    @Test
    fun rotationKeepsTheSideAndTheVerticalPlace() {
        val portrait = BubbleGeometry(1080, 2400, 147, 21, BubbleGeometry.Insets(0, 63, 0, 126))
        val landscape = BubbleGeometry(2400, 1080, 147, 21, BubbleGeometry.Insets(126, 63, 0, 0))
        val saved = portrait.snap(x = 900, y = 1_200)
        val (x, y) = landscape.place(saved)
        assertEquals(Side.Right, saved.side)
        assertEquals(2400 - 21 - 147, x)
        val fraction = (y - landscape.minY).toFloat() / (landscape.maxY - landscape.minY)
        assertEquals(saved.yFraction, fraction, 0.01f)
    }

    @Test
    fun leftSideStaysClearOfASideCutout() {
        val landscape = BubbleGeometry(2400, 1080, 147, 21, BubbleGeometry.Insets(126, 63, 0, 0))
        assertEquals(126 + 21, landscape.place(BubblePosition(Side.Left, 0.5f)).first)
    }

    @Test
    fun hideTargetIsACircle() {
        val g = phone(360, density = 1f)
        assertTrue(g.isOverHideTarget(180f, 600f, 180f, 600f, 48f))
        assertTrue(g.isOverHideTarget(180f + 48f, 600f, 180f, 600f, 48f))
        assertFalse(g.isOverHideTarget(180f + 35f, 600f + 35f, 180f, 600f, 48f)) // 49.5 px away
    }
}
