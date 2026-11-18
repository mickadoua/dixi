package tech.dmconsulting.dixi.bubble

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the bubble shows: its screen [side] and the y of its center, in screen pixels. */
data class BubbleAnchor(val side: Side, val centerY: Int)

/** A text a dictation just copied. [id] grows with each one: the same words twice still count. */
data class DictatedText(val id: Long, val text: String)

/**
 * What the bubble does, for the app screens: the guided trial follows the real gestures
 * (mockups DMC-764 § 3). Set by [BubbleService] and [BubbleOverlay], on the main thread.
 */
object BubbleEvents {
    private val stateFlow = MutableStateFlow(BubbleState.Idle)
    private val dockedFlow = MutableStateFlow(false)
    private val anchorFlow = MutableStateFlow<BubbleAnchor?>(null)
    private val textFlow = MutableStateFlow<DictatedText?>(null)
    private val errorFlow = MutableStateFlow<Int?>(null)

    val state: StateFlow<BubbleState> = stateFlow.asStateFlow()
    val docked: StateFlow<Boolean> = dockedFlow.asStateFlow()

    /** Null while the bubble is not on screen. */
    val anchor: StateFlow<BubbleAnchor?> = anchorFlow.asStateFlow()
    val lastText: StateFlow<DictatedText?> = textFlow.asStateFlow()

    /** Cause of the last error, while the bubble shows it. */
    val errorMessage: StateFlow<Int?> = errorFlow.asStateFlow()

    fun setState(state: BubbleState) {
        if (state != BubbleState.Error) errorFlow.value = null
        stateFlow.value = state
    }

    fun setDocked(docked: Boolean) {
        dockedFlow.value = docked
    }

    fun place(side: Side, centerY: Int) {
        anchorFlow.value = BubbleAnchor(side, centerY)
    }

    fun removed() {
        anchorFlow.value = null
    }

    fun copied(text: String) {
        textFlow.value = DictatedText((textFlow.value?.id ?: 0) + 1, text)
    }

    fun failed(@StringRes message: Int) {
        errorFlow.value = message
    }
}
