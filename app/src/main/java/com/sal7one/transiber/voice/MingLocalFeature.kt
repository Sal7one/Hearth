package com.sal7one.transiber.voice

import android.content.Context

/**
 * Explicit, off-by-default gate for the experimental Android Ming-3.0 local engine.
 *
 * Enabling this flag only makes the family selectable: availability still requires the arm64
 * JNI in the APK, and the 850 MB bundle must still be imported or downloaded and verified.
 * Nothing here claims an in-app device run or turns a missing runtime into success.
 */
internal object MingLocalFeature {
    private const val ENABLED_KEY = "ming-experimental-enabled"

    fun enabled(context: Context): Boolean =
        isEnabled(VoiceSettings.prefs(context).getBoolean(ENABLED_KEY, false))

    fun setEnabled(context: Context, enabled: Boolean) {
        VoiceSettings.prefs(context).edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    /** Persisted value policy; null (never written) is off. */
    internal fun isEnabled(stored: Boolean?): Boolean = stored == true
}
