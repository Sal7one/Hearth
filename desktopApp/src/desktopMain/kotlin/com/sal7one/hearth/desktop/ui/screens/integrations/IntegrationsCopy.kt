package com.sal7one.hearth.desktop.ui.screens.integrations

/** Copy for the integration page. Limits are stated as limits. */
internal object IntegrationsCopy {
    const val EYEBROW = "Integrations"
    const val TITLE = "Connect Hearth to your apps"
    const val LEDE =
        "Share captions with OBS, translate website text, or send images and audio through your saved pipeline. The service listens only on this computer."
    const val START = "Start connection"
    const val STARTING = "Starting…"
    const val STOP = "Stop connection"
    const val STOPPING = "Stopping…"
    const val LOOPBACK = "This computer only"

    const val RUNNING = "Running"
    const val STOPPED = "Stopped"
    const val SHARED = "Connection ready for OBS and your apps"
    const val NOT_SHARED = "Connection is off"

    const val OBS = "Put Hearth captions in OBS"
    const val OBS_DETAIL = "Four steps to captions over your stream. No OBS plugin or API key is needed."
    const val RECOMMENDED = "Recommended"
    const val OVERLAY_URL = "OBS Browser Source URL"
    const val COPY = "Copy OBS URL"
    const val OVERLAY_NOTE =
        "This private link can read captions only. Copy the complete link into OBS; its final # section is required."
    const val SHOW_ORIGINAL = "Show the original line"
    const val SHOW_ORIGINAL_DETAIL = "Smaller text above the translation."

    const val API = "External apps"
    const val API_DETAIL = "Send text, images or audio through your saved pipeline."
    const val BETA = "Beta"
    const val API_TOKEN = "API token"
    const val COPY_TOKEN = "Copy token"
    const val SEND = "Send a sentence"
    const val ACCEPTS = "Accepts"
    const val STOP_NOTE =
        "Stopping this connection disconnects OBS and cancels jobs sent by external apps. Captions started in Hearth keep running. After restarting, copy the new OBS URL."
    const val NOT_RUNNING_NOTE = "Start the connection above to create your private OBS link. Starting the connection does not start captions."
}
