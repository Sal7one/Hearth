package com.sal7one.transiber.home

/** Stable IDs survive future carousel reordering; ordinals are never persisted. */
internal enum class HomeService(val id: String, val title: String, val description: String, val action: String) {
    CAPTIONS("captions", "Live captions", "Read or translate speech from videos and voices around you.", "Open captions"),
    CONVERSATION("conversation", "Conversation", "Speak in two languages. Keep both sides of the conversation.", "Start a conversation"),
    FACE("face", "Face to face", "Share one phone with two people, each facing their own translation.", "Open face to face"),
    TEXT("text", "Type to translate", "Write or paste something. Read the translation or hear it aloud.", "Translate text"),
    CAMERA("camera", "Camera & photos", "Point at a sign, a menu or a page. Or choose an image from your phone.", "Open camera"),
    SCREEN("screen", "Screen & manga", "Read manga, books and other apps with translation over the page.", "Open screen translator"),
    SIGN("sign", "Sign language", "Read fingerspelling from the camera and type it as text over other apps.", "Open sign language");

    companion object {
        fun restore(id: String?): HomeService = entries.firstOrNull { it.id == id } ?: CAPTIONS
        fun forPage(page: Int, faceToFace: Boolean): HomeService? = when (page) {
            0 -> CAPTIONS
            7 -> if (faceToFace) FACE else CONVERSATION
            11 -> TEXT
            10 -> CAMERA
            16 -> SIGN
            else -> null // Browsing settings never replaces the last feature.
        }
    }
}
