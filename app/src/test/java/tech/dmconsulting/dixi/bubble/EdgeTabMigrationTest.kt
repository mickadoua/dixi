package tech.dmconsulting.dixi.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeTabMigrationTest {

    @Test
    fun bubbleHiddenToTheEdgeTabComesBackTucked() {
        assertEquals(BubbleFlags(hidden = false, docked = true), edgeTabMigration(dockedSaved = false, hidden = true, hideToEdge = true))
    }

    @Test
    fun bubbleHiddenToTheNotificationStaysHidden() {
        assertEquals(BubbleFlags(hidden = true, docked = false), edgeTabMigration(dockedSaved = false, hidden = true, hideToEdge = false))
    }

    @Test
    fun shownBubbleStaysShownAndWhole() {
        assertEquals(BubbleFlags(hidden = false, docked = false), edgeTabMigration(dockedSaved = false, hidden = false, hideToEdge = true))
    }

    @Test
    fun bubbleHiddenAfterTheMoveIsNotTouched() {
        assertNull(edgeTabMigration(dockedSaved = true, hidden = true, hideToEdge = true))
    }
}
