package com.sal7one.transiber.setup

/** One Back contract shared by the app header and Android Back. No credentials or form data. */
internal enum class EasySetupStep {
    CHOICE, LOCAL, CLOUD, LOCAL_DONE, CLOUD_DONE;

    val finished: Boolean get() = this == LOCAL_DONE || this == CLOUD_DONE
    val local: Boolean get() = this == LOCAL || this == LOCAL_DONE

    /** Null means leave the wizard for its caller. */
    fun back(): EasySetupStep? = when (this) {
        CHOICE -> null
        LOCAL, CLOUD -> CHOICE
        LOCAL_DONE -> LOCAL
        CLOUD_DONE -> CLOUD
    }

    fun complete(): EasySetupStep = when (this) {
        LOCAL -> LOCAL_DONE
        CLOUD -> CLOUD_DONE
        else -> this
    }
}
