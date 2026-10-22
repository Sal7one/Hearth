package com.sal7one.hearth.desktop.ui.screens.connections

/** Copy for connections. It states where keys go and what is never sent. */
internal object ConnectionsCopy {
    const val EYEBROW = "Settings"
    const val TITLE = "Connections"
    const val LEDE =
        "Your own endpoints and keys, plus models you bring as files. Nothing here is required for a " +
            "local-only setup."
    const val PROVIDERS = "Provider connections"
    const val PROVIDERS_DETAIL =
        "Any endpoint that speaks the OpenAI shape works. Local servers may need no key."
    const val USE = "Use this connection"
    const val IN_USE = "In use"
    const val NOT_IN_USE = "Not in use"

    const val ENDPOINT = "Endpoint"
    const val ENDPOINT_HINT = "Base URL only. No query string, no credentials inside the URL."
    const val MODEL = "Model"
    const val MODEL_HINT = "The model name the endpoint expects."
    const val CREDENTIAL = "Credential reference"
    const val CREDENTIAL_HINT = "A name, or env:VARIABLE to resolve a key you already export."
    const val KEY = "Key"
    const val KEY_HINT = "Stored in the OS store. Never written to settings or exported."
    const val SAVE = "Save"
    const val SAVE_KEY = "Save key"
    const val FORGET_KEY = "Forget key"
    const val KEY_SAVED = "Key configured"

    const val WHERE_KEYS = "Where keys live"
    const val WHERE_KEYS_DETAIL =
        "Provider keys go to the OS credential store — the macOS Keychain, or your user's DPAPI store on Windows — never to a settings file."
    const val STORED = "Stored"
    const val SHOWN = "Shown"
    const val EXPORTED = "Exported"
    const val ENV_TITLE = "Environment references"
    const val ENV_DETAIL = "Instead of storing a key, point at one you already export."
    const val ENV_NOTE = "Resolved when a request runs. An unset variable is an error, not an empty key."
    const val NEVER = "What is never sent"
    const val NEVER_DETAIL =
        "Keys are only ever sent to the connection you typed them for. Model downloads never receive a provider key."
}
