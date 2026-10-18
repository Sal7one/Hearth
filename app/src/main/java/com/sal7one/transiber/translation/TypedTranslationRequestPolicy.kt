package com.sal7one.transiber.translation

/** Orders an explicit retry after the corresponding settings revision reaches the UI. */
internal class TypedTranslationRequestPolicy {
    enum class Action { KEEP, CLEAR, DEBOUNCED, IMMEDIATE }
    private var requestedRevision: Long? = null

    fun requestImmediateAfter(connectionRevision: Long) { requestedRevision = connectionRevision }

    fun takeAction(automatic: Boolean, connectionRevision: Long): Action {
        requestedRevision?.let { required ->
            if (connectionRevision < required) return Action.KEEP
            requestedRevision = null
            return Action.IMMEDIATE
        }
        return if (automatic) Action.DEBOUNCED else Action.CLEAR
    }
}
