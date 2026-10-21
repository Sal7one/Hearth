package com.sal7one.hearth.desktop.ui.screens.home

import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.art.HearthArt

/**
 * The Home map. Copy is product language: no engine formats, no file sizes, no
 * implementation nouns in a primary flow. The footnote on each tile answers the
 * only question that matters before a click: can I use this here?
 */
internal object HomeCopy {
    const val EYEBROW = "Live captions · ready"
    const val HEADLINE_LINE_ONE = "Hear the room."
    const val HEADLINE_LINE_TWO = "Read it in your language."
    const val LEDE =
        "Speech, translation and voice are separate stages. Turn on the ones you need — on this Mac, " +
            "or through a provider you already use."
    const val START = "Start listening"
    const val MAP_EYEBROW = "The map"
    const val MAP_TITLE = "Everything Hearth does"
    const val MAP_LEDE =
        "Six ways in, one pipeline underneath. Entries marked Mobile live on the phone today, in the same design language."
    const val OPEN_WORKSPACE = "Open workspace"

    val features: List<HomeFeature> = listOf(
        HomeFeature(
            id = "conversation",
            title = "Conversation",
            description = "Two people, two languages. Every turn kept, nothing lost between them.",
            available = Availability.MOBILE,
            art = HearthArt.CONVERSATION,
            destination = DesktopScreen.CONVERSATION,
        ),
        HomeFeature(
            id = "face",
            title = "Face to face",
            description = "Share one screen across a table. Each side reads its own translation.",
            available = Availability.MOBILE,
            art = HearthArt.FACE,
            destination = DesktopScreen.CONVERSATION,
        ),
        HomeFeature(
            id = "text",
            title = "Type to translate",
            description = "Write or paste anything. Read it back, or have it spoken aloud.",
            available = Availability.DESKTOP,
            art = HearthArt.TEXT,
            destination = DesktopScreen.TRANSLATE,
        ),
        HomeFeature(
            id = "camera",
            title = "Camera & photos",
            description = "A menu, a sign, a page. Point at it and read it in your language.",
            available = Availability.MOBILE,
            art = HearthArt.CAMERA,
            destination = DesktopScreen.CAMERA,
        ),
        HomeFeature(
            id = "reading",
            title = "Screen & manga",
            description = "Read books and comics with translation laid over the page.",
            available = Availability.MOBILE,
            art = HearthArt.SCREEN,
            destination = DesktopScreen.READING,
        ),
        HomeFeature(
            id = "integration",
            title = "OBS & other apps",
            description = "Browser Source for OBS, plus a local API any app can call.",
            available = Availability.DESKTOP,
            art = null,
            destination = DesktopScreen.INTEGRATIONS,
        ),
    )

    val quickLinks: List<HomeFeature> = listOf(
        HomeFeature(
            id = "overlay",
            title = "Overlay",
            description = "Float captions over any app",
            available = Availability.DESKTOP,
            destination = DesktopScreen.OVERLAY,
        ),
        HomeFeature(
            id = "presets",
            title = "Presets",
            description = "The whole pipeline, one click",
            available = Availability.DESKTOP,
            destination = DesktopScreen.PRESETS,
        ),
        HomeFeature(
            id = "downloads",
            title = "Downloads",
            description = "Transfers and verification",
            available = Availability.DESKTOP,
            destination = DesktopScreen.DOWNLOADS,
        ),
        HomeFeature(
            id = "connections",
            title = "Connections",
            description = "Your own endpoints and keys",
            available = Availability.DESKTOP,
            destination = DesktopScreen.CONNECTIONS,
        ),
    )
}
