package com.sal7one.common_jni.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReleasingPooledChannelTest {
    private data class Item(val id: Int)

    @Test
    fun overflowDropsAndReleasesLatestItem() = runBlocking {
        val released = mutableListOf<Item>()
        val queue = ReleasingPooledChannel<Item>(capacity = 1, release = released::add)
        val first = Item(1)
        val latest = Item(2)

        queue.offer(first)
        queue.offer(latest)

        assertEquals(listOf(latest), released)
        assertSame(first, queue.flow.first())
        queue.cancel()
    }

    @Test
    fun discardAndCancellationReleaseEveryQueuedItem() {
        val released = mutableListOf<Item>()
        val queue = ReleasingPooledChannel<Item>(capacity = 2, release = released::add)
        val first = Item(1)
        val second = Item(2)
        queue.offer(first)
        queue.offer(second)

        queue.discardPending()
        assertEquals(listOf(first, second), released)

        val third = Item(3)
        queue.offer(third)
        queue.cancel()
        assertEquals(listOf(first, second, third), released)
    }
}
