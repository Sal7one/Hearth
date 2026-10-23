package com.sal7one.hearth.desktop.ui.screens.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sal7one.hearth.desktop.ui.screens.companion.CompanionScreen

/** Camera and photo import are phone features; the page says so. */
@Composable
internal fun CameraScreen(modifier: Modifier = Modifier) {
    CompanionScreen(
        eyebrow = "On mobile · camera & photos",
        title = "Read what you can’t",
        lede = "A menu, a sign, the label on a parcel. Point the camera, or pick a photo you already took.",
        notes = listOf(
            "In place" to "Hearth marks the lines it found before translating them, so you can see which words belong to which thing.",
            "Good at" to "Printed menus, signs and labels; pages, packaging and screens. Handwriting stays hit and miss.",
        ),
        callout = "Camera and photo import are part of the phone app. The desktop build reads audio and video frames today, not still images.",
        modifier = modifier,
    ) { CameraPhone() }
}
