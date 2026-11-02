package com.forcequit.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What to do to each app. Each one is a button on the app's App info page in Settings. */
enum class Action(val verbing: String, val pastTense: String) {
    FORCE_STOP("Force stopping", "force stopped"),
    DISABLE("Disabling", "disabled"),
    ENABLE("Enabling", "enabled"),
}

enum class Outcome {
    DONE,
    /** Already stopped / disabled / enabled before we started. */
    ALREADY_DONE,
    BUTTON_NOT_FOUND,
    /** The button was there but greyed out, e.g. a core system app that can't be disabled. */
    BUTTON_GREYED_OUT,
    CONFIRM_NOT_FOUND,
    NOT_VERIFIED;

    val succeeded get() = this == DONE || this == ALREADY_DONE
}

data class StopResult(val packageName: String, val label: String, val outcome: Outcome)

sealed interface RunState {
    data object Idle : RunState
    data class Running(val action: Action, val index: Int, val total: Int, val currentLabel: String) : RunState
    data class Finished(val action: Action, val results: List<StopResult>, val cancelled: Boolean) : RunState
}

/** Shared state between [ForceStopService] and the UI. */
object ForceStopController {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state

    internal fun update(state: RunState) {
        _state.value = state
    }

    fun dismissResults() {
        if (_state.value is RunState.Finished) _state.value = RunState.Idle
    }

    /** Returns false if the accessibility service isn't connected. */
    fun start(packages: List<String>, action: Action = Action.FORCE_STOP): Boolean {
        val service = ForceStopService.instance ?: return false
        service.start(packages, action)
        return true
    }

    fun cancel() {
        ForceStopService.instance?.cancel()
    }
}
