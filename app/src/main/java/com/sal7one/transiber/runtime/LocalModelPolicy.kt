package com.sal7one.transiber.runtime

import android.content.Context
import com.sal7one.transiber.caption.TvControls

/** Device policy is checked at execution and transfer boundaries, not just in the picker. */
internal object LocalModelPolicy {
    fun allowed(context: Context): Boolean = !TvControls.isTvDevice(context)

    fun requireAllowed(context: Context, feature: String) = requireAllowed(!allowed(context), feature)

    internal fun requireAllowed(television: Boolean, feature: String) {
        check(!television) {
            "Local models are disabled on TVs ($feature). Choose a configured cloud connection."
        }
    }
}
