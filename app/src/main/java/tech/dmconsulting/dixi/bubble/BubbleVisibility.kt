package tech.dmconsulting.dixi.bubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True while the bubble is on screen (not hidden, not only the edge tab). Set by
 * [BubbleService], read by the « Afficher la bulle » switch: the « Touchez, parlez »
 * button also shows the bubble, without the saved setting (DMC-433).
 */
object BubbleVisibility {
    private val visibleState = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun set(visible: Boolean) {
        visibleState.value = visible
    }
}
