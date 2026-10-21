package com.sal7one.hearth.desktop.ui.screens.files

/** Copy for storage. Paths are stated plainly; nothing is described as managed when it is not. */
internal object FilesCopy {
    const val EYEBROW = "Settings"
    const val TITLE = "Files & storage"
    const val LEDE =
        "Everything Hearth keeps on this Mac, and how to move it somewhere else. Paths are ordinary " +
            "folders — you own them."
    const val OPEN = "Open data folder"

    const val LOCATIONS = "Locations"
    const val LOCATIONS_DETAIL = "Change a folder and Hearth moves nothing — it reads from the new one."
    const val SHOW = "Show"
    const val DATA = "Data"
    const val DATA_DETAIL = "Settings & state"
    const val MODELS = "Models"
    const val MODELS_DETAIL = "Verified model files"
    const val SESSIONS = "Sessions & exports"
    const val SESSIONS_DETAIL = "Transcripts and generated audio"
    const val LOGS = "Logs"
    const val LOGS_DETAIL = "Diagnostics"

    const val STORAGE = "Storage"
    const val STORAGE_DETAIL = "Model weights are the only thing worth deleting; the rest is small."
    const val MODELS_STORAGE = "Models"

    const val PORTABLE = "Portable setup"
    const val PORTABLE_DETAIL =
        "Stages, languages and safe options. No keys, no credential references, no machine paths."
    const val EXPORT = "Export setup…"
    const val IMPORT = "Import setup…"
    const val PORTABLE_NOTE =
        "An imported setup still needs its models and keys resolved on this machine. Hearth says which ones are missing instead of running half-configured."

    const val DISK = "Disk"
    const val HEALTHY = "Healthy"
    const val DISK_DETAIL = "A download stops before it fills the disk, with the real error."
    const val ADVANCED = "Advanced"
    const val ADVANCED_DETAIL = "For development only. These override the folders above for one launch."
    const val ENV = "HEARTH_DATA_DIRECTORY=/tmp/hearth\n-Dhearth.native.dir=/path/to/lib"
}
