package com.sal7one.transiber.voice

import org.junit.Assert.*
import org.junit.Test

class MediaVolumeControllerTest {
    private class Port(var level: MediaVolumeLevel) : MediaVolumePort {
        val writes = mutableListOf<Int>()
        var error: RuntimeException? = null
        override fun read() = level
        override fun setIndex(index: Int) {
            error?.let { throw it }
            writes += index; level = level.copy(index = index)
        }
    }
    @Test fun openingAndReadingNeverChangesTheDeviceLevel() {
        val port = Port(MediaVolumeLevel(7, 0, 15, false))
        val controller = MediaVolumeController(port)
        assertEquals(47, controller.read().percent)
        port.level = port.level.copy(index = 12) // A hardware key changed it.
        assertEquals(80, controller.read().percent)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun sliderHonorsNonzeroMinimumAndTheActualDeviceStepCount() {
        val port = Port(MediaVolumeLevel(7, 2, 15, false))
        val controller = MediaVolumeController(port)
        assertEquals(53, controller.setPercent(50).percent) // Android's eighth step.
        assertEquals(13, controller.setPercent(-1).percent) // Minimum is not mute.
        assertEquals(100, controller.setPercent(200).percent)
        assertEquals(listOf(8, 2, 15), port.writes)
    }
    @Test fun fixedOrSingleStepDevicesAreNotWritten() {
        for (level in listOf(MediaVolumeLevel(5, 0, 10, true), MediaVolumeLevel(0, 0, 0, false))) {
            val port = Port(level)
            try { MediaVolumeController(port).setPercent(50); fail("fixed volume must reject writes") }
            catch (e: IllegalStateException) { assertEquals("This device has fixed media volume", e.message) }
            assertTrue(port.writes.isEmpty())
        }
    }
    @Test fun setterFailureIsPreservedAndDoesNotBecomeSuccess() {
        val port = Port(MediaVolumeLevel(7, 0, 15, false))
        val expected = SecurityException("volume is managed by device policy")
        port.error = expected
        try { MediaVolumeController(port).setPercent(90); fail("policy error must propagate") }
        catch (e: SecurityException) { assertSame(expected, e) }
        assertEquals(7, port.level.index)
    }
    @Test fun eachGestureRechecksTheCurrentOutputRouteAndAvoidsRedundantWrites() {
        val port = Port(MediaVolumeLevel(3, 0, 15, false))
        val controller = MediaVolumeController(port)
        port.level = MediaVolumeLevel(30, 0, 100, false) // Bluetooth route changed.
        assertEquals(30, controller.setPercent(30).percent)
        assertTrue(port.writes.isEmpty())
        assertEquals(50, controller.setPercent(50).percent)
        assertEquals(listOf(50), port.writes)
    }
    @Test fun invalidPlatformRangeFailsWithoutNonfiniteSliderValues() {
        try { MediaVolumeLevel(0, 0, -1, false); fail("invalid range must fail") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("invalid media volume range")) }
    }
}
