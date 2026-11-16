package tech.dmconsulting.dixi.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteTargetTest {
    private val own = "tech.dmconsulting.dixi"
    private val other = "com.android.contacts"

    private fun target(
        editable: Boolean = true,
        focused: Boolean = true,
        password: Boolean = false,
        fieldPackage: CharSequence? = other,
    ) = isPasteTarget(editable, focused, password, fieldPackage, own)

    @Test
    fun focusedEditableFieldOfAnotherAppIsATarget() {
        assertTrue(target())
    }

    @Test
    fun passwordFieldIsNotATarget() {
        assertFalse(target(password = true))
    }

    @Test
    fun ownFieldIsNotATarget() {
        assertFalse(target(fieldPackage = own))
    }

    @Test
    fun readOnlyOrUnfocusedFieldIsNotATarget() {
        assertFalse(target(editable = false))
        assertFalse(target(focused = false))
    }
}
