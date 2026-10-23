package com.sal7one.hearth.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.core.vision.*
import com.sal7one.hearth.desktop.ui.screens.reading.ReadingScreen
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import kotlin.test.*

/** Renders actual reader composables, without launching or controlling the owner's Mac app. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopMangaReadingRenderTest {
    @Test fun threePipelineChoicesAndArabicEnglishMasksRenderAtDesktopAndNarrowWidths() {
        val image=BufferedImage(500,700,BufferedImage.TYPE_INT_ARGB)
        val graphics=image.createGraphics()
        try {
            graphics.color=Color(239,233,222);graphics.fillRect(0,0,500,700)
            graphics.color=Color(43,50,61);graphics.fillRect(18,18,464,300);graphics.fillRect(18,335,464,347)
            graphics.color=Color.WHITE;graphics.fillOval(250,40,175,180);graphics.fillOval(70,390,190,170)
            graphics.color=Color.BLACK
            repeat(4){graphics.fillRect(302+it*12,75,5,82);graphics.fillRect(110+it*12,435,5,74)}
        } finally {graphics.dispose()}
        fun region(x: Int,y: Int,w: Int,h: Int,layout: OcrRect): OcrRegion {
            val spans=IntArray(h*3);repeat(h){spans[it*3]=y+it;spans[it*3+1]=x;spans[it*3+2]=w}
            val mask=ComicMask(spans,500,700)
            return OcrRegion(OcrRect(x,y,w,h),.95f,paint=MangaPaint(mask,layout,-1,0xff111111.toInt()))
        }
        val english=region(300,70,60,100,OcrRect(270,60,135,140))
        val arabic=region(100,425,65,90,OcrRect(90,412,150,120))
        val state=DesktopReadingState(image=image,name="Synthetic dialogue page",phase="2 regions read · 2 translated",
            regions=listOf(english,arabic),text=listOf(PageText(english,"今日はいい天気ですね。"),PageText(arabic,"新しい旅が始まる。")),
            translations=mapOf(english.bounds to "The sky is clear today.",arabic.bounds to "هذه بداية رحلة جديدة."))
        val directory=Path.of("build/reading-visual-qa")
        Files.createDirectories(directory)
        for(width in listOf(820,1500)) {
            val scene=ImageComposeScene(width=width,height=1300,density=Density(1f)) {
                HearthTheme {ReadingScreen(state=state,modelReady=true,modelId="manga-koharu-clean-page",
                    modelBytes=MangaArtifacts.filesForKoharuCleanup.sumOf {it.bytes})}
            }
            try {
                repeat(3){scene.render(System.nanoTime()).close()}
                val bytes=scene.render(System.nanoTime()).use {snapshot ->
                    snapshot.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Reader screenshot encoding failed")
                }
                val output=directory.resolve("manga-cleanup-$width.png");Files.write(output,bytes)
                val rendered=ImageIO.read(output.toFile())
                assertEquals(width,rendered.width);assertEquals(1300,rendered.height)
                assertTrue(bytes.size>10000,"Reader content must render beyond an empty surface")
            } finally {scene.close()}
        }
    }
}
