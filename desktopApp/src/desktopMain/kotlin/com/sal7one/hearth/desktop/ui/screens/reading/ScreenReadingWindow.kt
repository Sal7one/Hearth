package com.sal7one.hearth.desktop.ui.screens.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.desktop.DesktopReadingState
import com.sal7one.hearth.desktop.DesktopScreenReadingState
import com.sal7one.hearth.desktop.platform.DesktopScreenCapture
import com.sal7one.hearth.desktop.platform.OverlayWindowPresentation
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import java.awt.image.BufferedImage

/** Only replacement pixels are drawn; the browser beneath remains interactive and visible. */
@Composable
internal fun ScreenReadingWindow(
    screen: DesktopScreenReadingState, reading: DesktopReadingState,
    original: Boolean, dark: Boolean, preset: AccentPreset,
    onPause: ()->Unit, onStop: ()->Unit, onShowApp: ()->Unit, onOriginal: ()->Unit,
    onNativeError: (String)->Unit,
) {
    val region=screen.region ?: return
    if(screen.overlayReady && !original) {
        Window(onCloseRequest=onStop,title="Hearth screen translations",icon=painterResource("hearth.png"),
            alwaysOnTop=true,undecorated=true,transparent=true,resizable=false,
            state=rememberWindowState(position=WindowPosition.Absolute(region.x.dp,region.y.dp),width=region.width.dp,height=region.height.dp)) {
            DisposableEffect(window) {
                window.isAutoRequestFocus=false
                window.setBounds(region.x,region.y,region.width,region.height)
                DesktopScreenCapture.register(window)
                onDispose {DesktopScreenCapture.unregister(window)}
            }
            OverlayWindowPresentation(window,true,onNativeError)
            ScreenReadingLayer(reading)
        }
    }
    val initial=remember(region) {
        val bounds=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map {it.defaultConfiguration.bounds}.firstOrNull {it.contains(region.rectangle())}
        val x=region.x.coerceAtLeast(bounds?.x ?: region.x)
        val y=(region.y+region.height+12).coerceAtMost((bounds?.let {it.y+it.height} ?: (region.y+region.height))-180)
        x to y
    }
    Window(onCloseRequest=onStop,title="Hearth screen reading",icon=painterResource("hearth.png"),alwaysOnTop=true,
        resizable=false,state=rememberWindowState(position=WindowPosition.Absolute(initial.first.dp,initial.second.dp),width=520.dp,height=190.dp)) {
        DisposableEffect(window) {window.isAutoRequestFocus=false;DesktopScreenCapture.register(window);onDispose {DesktopScreenCapture.unregister(window)}}
        HearthTheme(dark=dark,preset=preset) {
            Column(Modifier.fillMaxSize().background(HearthTheme.colors.bg).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(reading.busy) reading.phase else screen.phase,style=HearthType.small,color=HearthTheme.colors.text)
                Text("Japanese screen text · ${screen.completedFrames} updates · clicks pass through",style=HearthType.micro,color=HearthTheme.colors.muted)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    HearthButton(if(screen.paused)"Resume" else "Pause",onPause,size=ButtonSize.Small)
                    HearthButton(if(original)"Translations" else "Original",onOriginal,tone=ButtonTone.Secondary,size=ButtonSize.Small)
                    HearthButton("Show Hearth",onShowApp,tone=ButtonTone.Secondary,size=ButtonSize.Small)
                    HearthButton("Stop",onStop,tone=ButtonTone.Ghost,size=ButtonSize.Small)
                }
            }
        }
    }
}

@Composable
internal fun ScreenReadingLayer(state: DesktopReadingState) {
    val image=state.image ?: return
    val cleanup=remember(image,state.text,state.translations.keys) {readingCleanupLayer(state).toComposeImageBitmap()}
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width=with(density){maxWidth.toPx()};val height=with(density){maxHeight.toPx()}
        val sx=width/image.width;val sy=height/image.height
        Canvas(Modifier.fillMaxSize()) {drawImage(cleanup,dstSize=IntSize(width.toInt(),height.toInt()))}
        state.text.forEach {item ->
            val text=state.translations[item.region.bounds] ?: return@forEach
            val paint=item.region.paint;val rect=paint?.layout ?: item.region.bounds
            Box(Modifier.offset {IntOffset((rect.x*sx).toInt(),(rect.y*sy).toInt())}
                .size(with(density){(rect.width*sx).toDp()},with(density){(rect.height*sy).toDp()})
                .background(if(paint==null)Color.White else Color.Transparent).padding(2.dp),contentAlignment=Alignment.Center) {
                BasicText(text,style=TextStyle(color=paint?.foreground?.let(::Color) ?: Color.Black,textAlign=TextAlign.Center,textDirection=TextDirection.Content),
                    autoSize=TextAutoSize.StepBased(8.sp,28.sp,1.sp),overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

/** Transparent replacement-only bitmap: artwork and unrecognized/untranslated text are never covered. */
internal fun readingCleanupLayer(state: DesktopReadingState): BufferedImage {
    val image=checkNotNull(state.image)
    val result=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
    val pixels=IntArray(image.width*image.height)
    state.text.filter {state.translations.containsKey(it.region.bounds)}.forEach {item ->item.region.paint?.let {paint ->
        val patch=paint.patch
        if(patch!=null)patch.forEachPixel {x,y,color ->pixels[y*image.width+x]=color}
        else paint.mask.forEachSpan {y,x,length ->java.util.Arrays.fill(pixels,y*image.width+x,y*image.width+x+length,paint.background)}
    }}
    result.setRGB(0,0,image.width,image.height,pixels,0,image.width)
    return result
}
