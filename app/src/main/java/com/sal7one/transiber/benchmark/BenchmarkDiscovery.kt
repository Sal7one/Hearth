package com.sal7one.transiber.benchmark

import kotlinx.coroutines.CancellationException

/** A broken install is an explicit discovery problem, never a reason to hide healthy models. */
internal class BenchmarkDiscovery {
    private val problems = mutableListOf<String>()
    val issues: List<String> get() = problems.toList()
    fun report(issue: String) { problems += issue }
    fun <T> inspect(label: String, block: () -> T): T? = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { report("$label: ${error.message ?: error.toString()}"); null }
}
