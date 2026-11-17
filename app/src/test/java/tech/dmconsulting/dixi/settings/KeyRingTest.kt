package tech.dmconsulting.dixi.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRingTest {
    private val two = KeyRing().add("a", "Perso").add("b", "Pro")

    @Test
    fun emptyRingHasNoKey() {
        assertEquals(KeyStatus.None, KeyRing().status)
        assertEquals(emptyList<KeyEntry>(), KeyRing().entries)
    }

    @Test
    fun theLastKeyAddedIsActive() {
        assertEquals("b", two.active)
        assertEquals(
            listOf(KeyEntry("a", "Perso", active = false, rejected = false), KeyEntry("b", "Pro", active = true, rejected = false)),
            two.entries,
        )
        assertEquals(KeyStatus.Saved, two.status)
    }

    @Test
    fun onlyOneKeyIsActive() {
        val ring = two.activate("a")
        assertEquals(listOf("a"), ring.entries.filter { it.active }.map { it.id })
        assertEquals(ring, ring.activate("unknown"))
    }

    @Test
    fun aRefusalBelongsToTheKeyThatWasActive() {
        val ring = two.markActive(rejected = true)
        assertEquals(KeyStatus.Rejected, ring.status)
        // Another key active: its own state counts.
        assertEquals(KeyStatus.Saved, ring.activate("a").status)
        assertEquals(KeyStatus.Rejected, ring.activate("a").activate("b").status)
        assertEquals(KeyStatus.Saved, ring.markActive(rejected = false).status)
    }

    @Test
    fun deletingTheActiveKeyHandsOverToTheFirstLeft() {
        val three = two.add("c", "Test")
        assertEquals("a", three.remove("c").active)
        assertEquals("c", three.remove("a").active)
        val last = three.remove("c").remove("a").remove("b")
        assertNull(last.active)
        assertEquals(KeyStatus.None, last.status)
    }

    @Test
    fun theRingRoundTripsAsJsonWithoutAnyKey() {
        val json = Json.encodeToString(KeyRing.serializer(), two.markActive(rejected = true))
        assertEquals(two.markActive(rejected = true), Json.decodeFromString<KeyRing>(json))
    }
}
