package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopWorkspaceControllerTest {
    @Test fun changingStagesRapidlyPreservesOtherRoutesAndLanguageSettings() = runTest {
        val initial = PipelineConfig(
            speech = EngineSelection("speech-a", modelId = "verified-a", modelPath = "/models/a"),
            translation = EngineSelection("translation-a"), voice = EngineSelection("voice-a"),
            sourceLanguage = "ar", targetLanguage = "en", voiceId = "F2", speakTranslatedText = false,
        )
        val config = MutableStateFlow(initial)
        val controller = controller(FakePipeline(), config, persist = {
            delay(10) // A real store write suspends; subsequent edits must read the committed configuration.
            config.value = it
        })
        controller.updateConfig { it.select(PipelineStage.TRANSLATION, null) }
        controller.updateConfig { it.select(PipelineStage.STT, EngineSelection("speech-b")) }
        runCurrent()
        advanceTimeBy(30)
        runCurrent()
        assertEquals(initial.copy(speech = EngineSelection("speech-b"), translation = null), config.value)
        controller.close()
    }

    @Test fun cancellationStaysBusyUntilPipelineHasActuallyJoined() = runTest {
        val pipeline = FakePipeline()
        val controller = controller(pipeline)
        controller.start(WorkspaceInput.TEXT, "Hello", "", null)
        runCurrent()
        controller.cancel()
        runCurrent()
        assertTrue(pipeline.session.cancelStarted.isCompleted)
        assertEquals(PipelineStatus.CANCELLED, controller.state.value.snapshot?.status)
        assertTrue(controller.state.value.cancelling)
        assertTrue(controller.state.value.active, "A terminal notification does not mean native work has joined")
        controller.start(WorkspaceInput.TEXT, "Another request", "", null)
        controller.retry()
        assertEquals(1, pipeline.requests.size)
        pipeline.session.releaseCancellation.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.cancelling)
        assertFalse(controller.state.value.active)
        controller.close()
    }

    @Test fun shutdownWaitsForSessionAndRejectsNewRequests() = runTest {
        val pipeline = FakePipeline()
        val controller = controller(pipeline)
        controller.start(WorkspaceInput.TEXT, "Hello", "", null)
        runCurrent()
        val closing = async { controller.close() }
        runCurrent()
        assertTrue(pipeline.session.cancelStarted.isCompleted)
        assertFalse(closing.isCompleted)
        controller.start(WorkspaceInput.TEXT, "Too late", "", null)
        assertEquals(1, pipeline.requests.size)
        pipeline.session.releaseCancellation.complete(Unit)
        closing.await()
        assertTrue(pipeline.session.cancelReturned)
    }

    @Test fun credentialFailureKeepsEditorValueAndSurfacesActualStoreError() = runTest {
        var clearEditor = false
        val controller = controller(FakePipeline(), putSecret = { _, _ -> throw IOException("Keychain access was denied") })
        controller.saveCredential("translation-provider", "typed-secret") { clearEditor = true }
        runCurrent()
        assertFalse(clearEditor, "The editor is cleared only after secure storage succeeds")
        assertEquals("Keychain access was denied", controller.state.value.error)
        controller.close()
    }

    @Test fun startingPipelineWaitsForImportedModelVerification() = runTest {
        val pipeline = FakePipeline()
        val imported = CompletableDeferred<Unit>()
        val controller = controller(pipeline, importSetup = { imported.await() })
        controller.importSetup(Path.of("setup.json"))
        runCurrent()
        assertTrue(controller.state.value.importingSetup)
        controller.start(WorkspaceInput.TEXT, "Not ready", "", null)
        assertTrue(pipeline.requests.isEmpty())
        assertEquals("Wait for setup verification to finish", controller.state.value.error)
        imported.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.importingSetup)
        controller.close()
    }

    @Test fun retryUsesOriginalTextAndCurrentRepairedConfiguration() = runTest {
        val pipeline = RetryPipeline()
        val config = MutableStateFlow(PipelineConfig(translation = EngineSelection("broken-provider")))
        val controller = controller(pipeline, config)
        controller.start(WorkspaceInput.TEXT, "مرحبا بالعالم", "", null)
        runCurrent()
        assertEquals(PipelineStatus.FAILED, controller.state.value.snapshot?.status)
        config.value = config.value.copy(translation = EngineSelection("repaired-provider"), targetLanguage = "fr")
        controller.retry()
        runCurrent()
        assertEquals(2, pipeline.requests.size)
        assertEquals(PipelineInput.Text("مرحبا بالعالم"), pipeline.requests.last().input)
        assertEquals(config.value, pipeline.requests.last().config)
        assertTrue(controller.state.value.active)
        controller.retry()
        assertEquals(2, pipeline.requests.size, "Retry cannot submit a second active request")
        controller.close()
        controller.retry()
        assertEquals(2, pipeline.requests.size, "Closed controllers cannot resend private input")
    }

    @Test fun retryReopensTheSameAudioFileAfterFailure() = runTest {
        val path = Files.createTempFile("hearth-retry-", ".wav")
        val pipeline = RetryPipeline()
        val controller = controller(pipeline)
        try {
            controller.start(WorkspaceInput.AUDIO_FILE, "", path.toString(), null)
            controller.state.first { !it.starting && it.snapshot != null }
            controller.retry()
            controller.state.first { !it.starting && pipeline.requests.size == 2 }
            assertEquals(PipelineInput.AudioFile(path.toAbsolutePath().toString()), pipeline.requests.last().input)
        } finally {
            controller.close()
            Files.deleteIfExists(path)
        }
    }

    @Test fun microphoneRetryCreatesAFreshUncollectedCaptureFlowWithoutOpeningADevice() = runTest {
        val pipeline = RetryPipeline()
        val controller = controller(pipeline)
        controller.start(WorkspaceInput.MICROPHONE, "", "", "selected-device")
        runCurrent()
        controller.retry()
        runCurrent()
        val first = assertIs<PipelineInput.AudioStream>(pipeline.requests.first().input)
        val second = assertIs<PipelineInput.AudioStream>(pipeline.requests.last().input)
        assertNotSame(first.frames, second.frames, "A consumed/failed microphone flow cannot be reused")
        assertTrue(controller.state.value.microphone)
        controller.close()
    }

    private fun TestScope.controller(
        pipeline: PipelineService,
        config: MutableStateFlow<PipelineConfig> = MutableStateFlow(PipelineConfig(translation = EngineSelection("translation"))),
        persist: suspend (PipelineConfig) -> Unit = { config.value = it },
        putSecret: suspend (String, String) -> Unit = { _, _ -> },
        importSetup: suspend (Path) -> Unit = {},
    ) = DesktopWorkspaceController(pipeline, config, Path.of("."), backgroundScope,
        persist, putSecret, removeSecret = {}, exportSetup = {}, importSetup = importSetup)

    private class FakePipeline : PipelineService {
        val requests = mutableListOf<PipelineRequest>()
        val session = JoiningSession()
        override fun capabilities() = emptyList<EngineDescriptor>()
        override fun start(request: PipelineRequest): PipelineSession {
            requests += request
            return session
        }
    }

    private class RetryPipeline : PipelineService {
        val requests = mutableListOf<PipelineRequest>()
        override fun capabilities() = emptyList<EngineDescriptor>()
        override fun start(request: PipelineRequest): PipelineSession {
            requests += request
            val initial = if (requests.size == 1) PipelineStatus.FAILED else PipelineStatus.RUNNING
            return object : PipelineSession {
                override val id = "retry-${requests.size}"
                override val state = MutableStateFlow(PipelineSnapshot(id, initial,
                    error = if (initial == PipelineStatus.FAILED) "Repair the selected provider" else null))
                override val events = MutableSharedFlow<PipelineEvent>()
                override suspend fun cancel() { state.value = state.value.copy(status = PipelineStatus.CANCELLED) }
            }
        }
    }

    private class JoiningSession : PipelineSession {
        override val id = "test-session"
        override val state = MutableStateFlow(PipelineSnapshot(id, PipelineStatus.RUNNING))
        override val events = MutableSharedFlow<PipelineEvent>()
        val cancelStarted = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        var cancelReturned = false
        override suspend fun cancel() {
            cancelStarted.complete(Unit)
            state.value = state.value.copy(status = PipelineStatus.CANCELLED)
            releaseCancellation.await()
            cancelReturned = true
        }
    }
}
