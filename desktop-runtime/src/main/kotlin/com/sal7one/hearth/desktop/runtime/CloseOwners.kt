package com.sal7one.hearth.desktop.runtime

/** Release every independent owner, retaining the first failure and all later cleanup evidence. */
internal fun closeOwners(vararg owners: () -> Unit) {
    var failure: Throwable? = null
    for (close in owners) try { close() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
