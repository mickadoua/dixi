package tech.dmconsulting.dixi.pastetarget

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A normal window with a text field and a password field, for the auto-paste emulator proof
 * (DMC-363). Android's own password screens are secure: they hide the bubble.
 * « focus » = text (default) or password gives that field the input focus at start.
 * The password stays masked: its length is shown, and logged, to prove a paste did not land.
 *
 *   adb shell am start -n tech.dmconsulting.dixi.pastetarget/.PasteTargetActivity --es focus password
 */
class PasteTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = EditText(this).apply {
            id = R.id.field_text
            hint = "Champ texte"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val password = EditText(this).apply {
            id = R.id.field_password
            hint = "Mot de passe"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val length = TextView(this).apply { id = R.id.password_length }
        password.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) = showLength(length, s?.length ?: 0)
            },
        )
        showLength(length, 0)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(text)
                addView(password)
                addView(length)
            },
        )
        val focused = if (intent.getStringExtra("focus") == "password") password else text
        focused.requestFocus()
    }

    private fun showLength(view: TextView, chars: Int) {
        view.text = "Mot de passe : $chars caractère(s)"
        Log.i(TAG, "password length=$chars")
    }

    private companion object {
        const val TAG = "PasteTarget"
    }
}
