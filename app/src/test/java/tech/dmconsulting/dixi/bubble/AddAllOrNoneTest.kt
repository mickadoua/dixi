package tech.dmconsulting.dixi.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AddAllOrNoneTest {

    private val attached = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun addAll(failOn: String?) = addAllOrNone(
        listOf("hideZone", "backdrop", "bubble"),
        add = {
            if (it == failOn) throw failure
            attached += it
        },
        remove = {
            attached -= it
            removed += it
        },
    )

    private val failure = IllegalStateException("permission denied for window type")

    @Test
    fun addsEveryWindowInOrder() {
        addAll(failOn = null)
        assertEquals(listOf("hideZone", "backdrop", "bubble"), attached)
        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun secondWindowRefusedRemovesTheFirst() {
        assertThrowsFailure { addAll(failOn = "backdrop") }
        assertEquals(emptyList<String>(), attached)
        assertEquals(listOf("hideZone"), removed)
    }

    @Test
    fun thirdWindowRefusedRemovesTheOthersLastFirst() {
        assertThrowsFailure { addAll(failOn = "bubble") }
        assertEquals(emptyList<String>(), attached)
        assertEquals(listOf("backdrop", "hideZone"), removed)
    }

    @Test
    fun aFailedRemoveDoesNotStopTheOthers() {
        val items = listOf("a", "b", "c")
        val left = mutableListOf<String>()
        assertThrowsFailure {
            addAllOrNone(
                items,
                add = { if (it == "c") throw failure else left += it },
                remove = { if (it == "b") throw IllegalArgumentException("not attached") else left -= it },
            )
        }
        assertEquals(listOf("b"), left)
    }

    private fun assertThrowsFailure(block: () -> Unit) {
        try {
            block()
            fail("expected the add failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
    }
}
