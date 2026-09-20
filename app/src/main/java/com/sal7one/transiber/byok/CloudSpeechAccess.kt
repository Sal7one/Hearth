package com.sal7one.transiber.byok

/** Only an explicitly selected compatible server may run without an API key. */
internal fun cloudSpeechKeyRequired(mode: CloudConfigStore.SttMode, provider: CloudConfigStore.Provider): Boolean =
    mode != CloudConfigStore.SttMode.BATCH || provider != CloudConfigStore.Provider.CUSTOM
