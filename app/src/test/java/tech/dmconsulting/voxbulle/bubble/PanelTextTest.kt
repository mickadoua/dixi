package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelTextTest {
    private val text = "Bonjour, je vous confirme le rendez-vous de jeudi à dix heures."

    @Test
    fun aTapInAWordSelectsTheWholeWord() {
        val jeudi = text.indexOf("jeudi")
        assertEquals(jeudi..jeudi + 4, wordAt(text, jeudi + 2))
    }

    @Test
    fun hyphensAndApostrophesStayInTheWord() {
        val rdv = text.indexOf("rendez-vous")
        assertEquals(rdv..rdv + 10, wordAt(text, rdv + 1))
        assertEquals(0..10, wordAt("aujourd'hui", 9))
    }

    @Test
    fun aTapJustAfterAWordStillMeansIt() {
        assertEquals(0..6, wordAt(text, 7))
    }

    @Test
    fun aTapBetweenTwoSpacesSelectsNothing() {
        assertNull(wordAt("a  b", 2))
        assertNull(wordAt("", 0))
    }

    @Test
    fun elapsedIsMinutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:12", formatElapsed(12_400))
        assertEquals("4:59", formatElapsed(299_999))
    }
}
