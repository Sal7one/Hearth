package com.sal7one.transiber.benchmark

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BenchmarkResultsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hundredsOfModelsKeepDistinctRowsAndMetadataAcrossReopen() {
        val directory = temporary.newFolder()
        val store = BenchmarkResults(directory)
        repeat(650) { store.append(result(it)) }
        val reopened = BenchmarkResults(directory)
        val results = reopened.load()
        assertEquals(650, results.size)
        assertEquals("model-649@vulkan", results.first().identity)
        assertEquals("vulkan", results.first().actualAcceleration)
        assertEquals("configuration-649", results.first().configurationHash)
        assertEquals(650, JSONArray(reopened.export()).length())
        assertEquals(650, File(directory, "benchmark-results-v2").listFiles()!!.size)
        reopened.clear()
        assertTrue(reopened.load().isEmpty())
    }

    @Test fun migratesLegacyHistoryWithoutLosingRowsOrDuplicatingInterruptedMigration() {
        val directory = temporary.newFolder()
        val old = result(1).json().apply {
            remove("task"); remove("requestedAcceleration"); remove("actualAcceleration"); remove("configurationHash")
        }
        val legacy = File(directory, "benchmark-results.json")
        legacy.writeText(JSONArray(listOf(old)).toString())
        val store = BenchmarkResults(directory)
        assertEquals(1, store.load().size)
        assertFalse(legacy.exists())
        assertNull(store.load().single().actualAcceleration)
        legacy.writeText(JSONArray(listOf(old)).toString())
        assertEquals(1, store.load().size)
        store.append(result(2).copy(task = "voice"))
        assertEquals(listOf("voice", "speech"), store.load().map { it.task })
    }

    @Test fun malformedLegacyFileIsPreservedAndReported() {
        val directory = temporary.newFolder()
        val legacy = File(directory, "benchmark-results.json").apply { writeText("broken") }
        assertThrows(Exception::class.java) { BenchmarkResults(directory).load() }
        assertTrue(legacy.exists())
    }

    @Test fun concurrentAppendsAndReadsNeverObservePartialJson() {
        val directory = temporary.newFolder()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0..3).map { worker -> Thread {
            try {
                val store = BenchmarkResults(directory)
                repeat(30) { index -> store.append(result(worker * 30 + index)); store.load() }
            } catch (error: Throwable) { failures += error }
        }.also(Thread::start) }
        threads.forEach(Thread::join)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(120, BenchmarkResults(directory).load().size)
    }

    private fun result(index: Int) = BenchmarkResult("batch", index.toLong(), "Model $index", "model-$index@vulkan",
        "speech", "input", "en", "", 1000, 10.0, listOf(90.0, 80.0, 70.0), listOf("hello"),
        "Vulkan · model buffer placement", listOf(20.0, 18.0, 17.0), "phone", protocolVersion = 2,
        requestedAcceleration = "vulkan", actualAcceleration = "vulkan", modelBytes = 100_000_000,
        configurationHash = "configuration-$index")
}
