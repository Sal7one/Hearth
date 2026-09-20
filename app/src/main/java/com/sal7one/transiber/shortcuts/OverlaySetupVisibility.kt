package com.sal7one.transiber.shortcuts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Prevent Hearth's own overlay windows from intercepting setup/consent taps, on every supported API. */
internal object OverlaySetupVisibility {
    private val owners = mutableSetOf<Any>()
    private val state = MutableStateFlow(false)
    val active: StateFlow<Boolean> = state
    @Synchronized fun acquire(): AutoCloseable {
        val token = Any()
        owners += token; state.value = true
        return AutoCloseable { release(token) }
    }
    @Synchronized private fun release(token: Any) {
        owners -= token; state.value = owners.isNotEmpty()
    }
}
