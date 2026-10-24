package com.sal7one.transiber.reading

import android.app.Application
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ReadingOverlayPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val raw get() = UserPreferences.store(context, "reading-overlay")
    @Before fun reset() = runBlocking {
        UserPreferences.initialize(context)
        assertTrue(raw.edit().clear().commit())
    }

    @Test fun changingOneOptionPreservesLatestOverlayChoicesAndPosition() = runBlocking {
        val store = ReadingOverlayPreferences(context)
        assertTrue(raw.edit().putInt("x", 135).putInt("y", 240)
            .putString("manga-profile", "manga-koharu-page").putString("mode", "page").commit())
        store.update { it.copy(passThrough = false, textSizeSp = 26f) }
        // Recreating the Activity or service reads the same saved snapshot.
        val restored = ReadingOverlayPreferences(context).read()
        assertEquals("page", restored.mode); assertFalse(restored.passThrough)
        assertEquals(26f, restored.textSizeSp)
        assertEquals(135, raw.getInt("x", 0)); assertEquals(240, raw.getInt("y", 0))
        assertEquals("manga-koharu-page", raw.getString("manga-profile", null))
    }

    @Test fun setupObservesOverlayChangesWithoutWritingStaleScreenStateBack() = runBlocking {
        val store = ReadingOverlayPreferences(context)
        val observed = mutableListOf<ReadingOverlaySettings>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { store.values().take(2).toList(observed) }
        assertTrue(raw.edit().putString("mode", "page").putFloat("textSize", 30f).commit())
        observer.join()
        assertEquals(listOf("manual", "page"), observed.map { it.mode })
        store.update { it.copy(showOriginal = true) }
        assertEquals(30f, store.read().textSizeSp)
        assertEquals("page", store.read().mode)
    }

    @Test fun ignoredAreasPersistAcrossReaderRecreationAndOrdinaryOptionUpdatesAndCanBeCleared() = runBlocking {
        val store = ReadingOverlayPreferences(context)
        val area = checkNotNull(ReadingMotionExclusion.fromCrop(com.sal7one.transiber.ocr.PixelCrop(10, 800, 1050, 960), 1080, 2400))
        store.update { it.copy(mode = "page", motionExclusions = listOf(area)) }
        store.update { it.copy(textSizeSp = 25f, passThrough = false) }
        val restored = ReadingOverlayPreferences(context).read()
        assertEquals(listOf(area), restored.motionExclusions)
        assertEquals("page", restored.mode)
        assertEquals(25f, restored.textSizeSp)
        assertFalse(restored.passThrough)
        assertEquals(listOf(area), ReadingMotionExclusions.decode(raw.getString("motion-exclusions", null)))
        store.update { it.copy(motionExclusions = emptyList()) }
        assertTrue(ReadingOverlayPreferences(context).read().motionExclusions.isEmpty())
        assertEquals(25f, store.read().textSizeSp)
    }
}
