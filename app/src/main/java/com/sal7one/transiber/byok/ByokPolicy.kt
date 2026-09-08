package com.sal7one.transiber.byok

import com.sal7one.transiber.BuildConfig

/** Network capability gate. Only play has network permissions; foss is local-only.
 * Cloud clients use user-supplied keys. Downloads never receive provider keys.
 * A failed cloud request surfaces its error without silently switching engines.
 */
object ByokPolicy {

    /** True only in the `play` distribution; foss builds have no network. */
    const val FEATURE_BYOK: Boolean = BuildConfig.FEATURE_BYOK

    /** Whether the cloud caption engine may be offered in the UI. */
    fun cloudEngineAvailable(): Boolean = FEATURE_BYOK
}
