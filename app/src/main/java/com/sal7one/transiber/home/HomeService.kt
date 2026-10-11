package com.sal7one.transiber.home

/** Stable IDs survive future carousel reordering; ordinals are never persisted. */
internal enum class HomeService(val id: String, val title: String, val description: String, val action: String) {
    CAPTIONS("captions", "Live captions", "Read or translate speech from videos and voices around you.", "Open captions"),
    CONVERSATION("conversation", "Conversation", "Speak in two languages. Keep both sides of the conversation.", "Start a conversation"),
    FACE("face", "Face to face", "Share one phone with two people, each facing their own translation.", "Open face to face"),
    TEXT("text", "Type to translate", "Write or paste something. Read the translation or hear it aloud.", "Translate text"),
    CAMERA("camera", "Camera & photos", "Point at a sign, a menu or a page. Or choose an image from your phone.", "Open camera"),
    SCREEN("screen", "Screen & manga", "Read manga, books and other apps with translation over the page.", "Open screen translator"),
    TTS("tts", "Read aloud", "Give text a voice, locally or with your cloud provider.", "Read aloud"),
    NOTES("notes", "Voice notes", "Record, listen and keep optional transcripts and translations.", "Record a note"),
    DOCUMENTS("documents", "Documents", "Extract text from PDFs, documents and scans.", "Open a document");

    companion object {
        fun restore(id: String?): HomeService = entries.firstOrNull { it.id == id } ?: CAPTIONS
        fun forPage(page: Int, faceToFace: Boolean): HomeService? = when (page) {
            0 -> CAPTIONS
            7 -> if (faceToFace) FACE else CONVERSATION
            11 -> TEXT
            10 -> CAMERA
            17 -> TTS
            18 -> NOTES
            19 -> DOCUMENTS
            else -> null // Browsing settings never replaces the last feature.
        }
    }
}
