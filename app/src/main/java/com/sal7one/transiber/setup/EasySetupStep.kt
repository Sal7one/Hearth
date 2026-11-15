package com.sal7one.transiber.setup

/** One Back contract shared by the app header and Android Back. No credentials or form data. */
internal enum class EasySetupStep {
    CHOICE, PRESETS, LOCAL, CLOUD, PRESETS_DONE, LOCAL_DONE, CLOUD_DONE;

    val finished: Boolean get() = this == PRESETS_DONE || this == LOCAL_DONE || this == CLOUD_DONE
    val local: Boolean get() = this == PRESETS || this == PRESETS_DONE || this == LOCAL || this == LOCAL_DONE

    /** Null means leave the wizard for its caller. */
    fun back(entry: EasySetupStep = CHOICE): EasySetupStep? = when (this) {
        CHOICE, PRESETS -> null
        LOCAL, CLOUD -> entry
        PRESETS_DONE -> PRESETS
        LOCAL_DONE -> LOCAL
        CLOUD_DONE -> CLOUD
    }

    fun complete(): EasySetupStep = when (this) {
        PRESETS -> PRESETS_DONE
        LOCAL -> LOCAL_DONE
        CLOUD -> CLOUD_DONE
        else -> this
    }
}
