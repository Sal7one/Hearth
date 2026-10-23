package com.sal7one.hearth.desktop.ui.screens.integrations

/** Copy for the integration page. Limits are stated as limits. */
internal object IntegrationsCopy {
    const val EYEBROW = "Integrations"
    const val TITLE = "Connect Hearth to your apps"
    const val LEDE =
        "Share captions with OBS, translate website text, or send images and audio through your saved pipeline. The service listens only on this computer."
    const val START = "Start server"
    const val STARTING = "Starting…"
    const val STOP = "Stop server"
    const val STOPPING = "Stopping…"
    const val LOOPBACK = "Loopback only"

    const val RUNNING = "Running"
    const val STOPPED = "Stopped"
    const val SHARED = "Live captions are being shared"
    const val NOT_SHARED = "The server is not running"

    const val OBS = "OBS Browser Source"
    const val OBS_DETAIL = "Transparent captions you can place over any scene."
    const val RECOMMENDED = "Recommended"
    const val OVERLAY_URL = "Overlay URL"
    const val COPY = "Copy"
    const val OVERLAY_NOTE =
        "The fragment holds a read-only caption key. It is not the API token and never appears in a request path."
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
        "Stopping the server revokes both keys immediately and cancels any session it started. Sessions you started in Hearth keep running."
    const val NOT_RUNNING_NOTE = "Start the server to get an API endpoint and an OBS overlay URL."
}
