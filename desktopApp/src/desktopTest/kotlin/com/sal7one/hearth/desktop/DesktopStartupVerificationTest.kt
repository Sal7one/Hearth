package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.screens.home.homeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopStartupVerificationTest {
    @Test fun savedQwenRouteSurvivesRestartAndWaitsForAuditWithoutAskingToConfigureAgain() = runTest {
        val directory=Files.createTempDirectory("hearth-startup-setup-")
        val library=DesktopModelLibrary(directory)
        val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
        val route=EngineSelection(card.engineId,card.id,directory.resolve("picked-qwen").toString(),options=mapOf("threads" to "6","acceleration" to "cpu"))
        val selected=PipelineConfig(speech=route,sourceLanguage="ru",targetLanguage="en")
        val first=DesktopSettingsStore(directory)
        first.save(selected);first.closeAndJoin()
        val reopened=DesktopSettingsStore(directory)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val controller=DesktopModelController(library,backgroundScope,{fail(it)},verifyModel={_,_ ->entered.complete(Unit);release.await();route})
        try {
            assertEquals(selected,reopened.load(),"Restart must restore the exact selected route, path and options")
            controller.refresh(reopened.config.value)
            assertEquals(mapOf(card.id to route.modelPath),controller.state.value.verifyingSelections)
            entered.await()
            val pending=home(reopened.config.value,controller,card)
            assertTrue(pending.setupVerifying);assertFalse(pending.recognitionReady)
            assertNull(pending.setupIssue,"Hashing an installed route is not a request to choose it again")
            assertEquals("Checking saved setup",pending.statusText)
            assertTrue(pending.verificationMessage.orEmpty().contains(card.name))
            release.complete(Unit)
            controller.state.first {!it.refreshing}
            val ready=home(reopened.config.value,controller,card)
            assertFalse(ready.setupVerifying);assertTrue(ready.recognitionReady)
            assertNull(ready.setupIssue);assertEquals("Ready",ready.statusText)
            assertEquals(selected,reopened.config.value,"Integrity checks must not overwrite the user's route/options")
        } finally {release.complete(Unit);controller.close();reopened.closeAndJoin();directory.toFile().deleteRecursively()}
    }

    @Test fun failedAuditShowsActualMissingFilesAndDoesNotStayPendingOrSilentlyResetTheSelection() = runTest {
        val directory=Files.createTempDirectory("hearth-startup-missing-")
        val library=DesktopModelLibrary(directory)
        val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
        val route=EngineSelection(card.engineId,card.id,directory.resolve("missing-qwen").toString())
        val selected=PipelineConfig(speech=route,sourceLanguage="en")
        val errors=mutableListOf<String>()
        val controller=DesktopModelController(library,backgroundScope,errors::add,verifyModel={_,_ ->error("Pinned component encoder.onnx is missing")})
        try {
            controller.refresh(selected);controller.state.first {!it.refreshing}
            val failed=home(selected,controller,card)
            assertFalse(failed.setupVerifying);assertFalse(failed.recognitionReady)
            assertTrue(failed.setupIssue.orEmpty().contains("encoder.onnx is missing"))
            assertEquals("Setup needed",failed.statusText)
            assertTrue(controller.state.value.verifyingSelections.isEmpty())
            assertEquals(route,selected.speech)
            assertTrue(errors.single().contains("encoder.onnx is missing"))
        } finally {controller.close();directory.toFile().deleteRecursively()}
    }

    @Test fun aPendingAuditForAnotherPathDoesNotHideAWrongSavedPathOrUnsupportedEngine() {
        val directory=Files.createTempDirectory("hearth-startup-other-path-")
        try {
            val library=DesktopModelLibrary(directory)
            val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
            val route=EngineSelection(card.engineId,card.id,directory.resolve("old").toString())
            val descriptor=EngineDescriptor(card.engineId,"Qwen3-ASR",PipelineStage.STT,EngineKind.LOCAL,requiresModel=true)
            val home=homeState(PipelineConfig(speech=route,sourceLanguage="en"),listOf(descriptor),1,1,"Ready",false,
                catalog=listOf(card),verifyingSelections=mapOf(card.id to directory.resolve("another").toString()))
            assertFalse(home.setupVerifying);assertNotNull(home.setupIssue);assertEquals("Setup needed",home.statusText)
        } finally {directory.toFile().deleteRecursively()}
    }

    @Test fun anAuditPendingForTheSavedPathOverridesPreviouslyReadyProof() {
        val directory=Files.createTempDirectory("hearth-refresh-old-proof-")
        try {
            val library=DesktopModelLibrary(directory)
            val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
            val path=directory.resolve("qwen")
            val config=PipelineConfig(speech=EngineSelection(card.engineId,card.id,path.toString()),sourceLanguage="en")
            val state=homeState(config,listOf(EngineDescriptor(card.engineId,"Qwen",PipelineStage.STT,EngineKind.LOCAL,requiresModel=true)),1,1,"Ready",false,
                catalog=listOf(card),installations=mapOf(card.id to DesktopModelStatus(true,path)),verifyingSelections=mapOf(card.id to path.toString()))
            assertTrue(state.setupVerifying);assertFalse(state.recognitionReady);assertEquals("Checking saved setup",state.statusText)
        } finally {directory.toFile().deleteRecursively()}
    }

    @Test fun changedSavedPathDuringAuditIsQueuedAndOldFailureCannotEraseItsPendingState() = runTest {
        val directory=Files.createTempDirectory("hearth-refresh-new-path-")
        val library=DesktopModelLibrary(directory)
        val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
        val old=EngineSelection(card.engineId,card.id,directory.resolve("old").toString())
        val fresh=old.copy(modelPath=directory.resolve("new").toString())
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val checked=mutableListOf<String>();val errors=mutableListOf<String>()
        val controller=DesktopModelController(library,backgroundScope,errors::add,verifyModel={_,path ->
            checked+=path.fileName.toString()
            if(path.fileName.toString()=="old") {entered.complete(Unit);release.await();error("old selection was removed")}
            fresh
        })
        try {
            controller.refresh(PipelineConfig(speech=old,sourceLanguage="en"));entered.await()
            controller.refresh(PipelineConfig(speech=fresh,sourceLanguage="en"))
            assertEquals(fresh.modelPath,controller.state.value.verifyingSelections[card.id])
            release.complete(Unit)
            controller.state.first {!it.refreshing}
            assertEquals(listOf("old","new"),checked)
            assertEquals(fresh,controller.verifiedSelection(card.id))
            assertTrue(errors.isEmpty(),"An obsolete route failure must not claim the current selected model failed")
        } finally {release.complete(Unit);controller.close();directory.toFile().deleteRecursively()}
    }

    @Test fun pausedDownloadForTheSameIdDoesNotPreventAuditingAHealthyExternalSelection() = runTest {
        val directory=Files.createTempDirectory("hearth-refresh-paused-same-id-")
        val initial=DesktopModelLibrary(directory)
        val card=initial.catalog.first {it.engineId=="qwen3-asr-local"}
        initial.downloads.queue(DesktopQueueEntry(card.id,DesktopQueuePhase.PAUSED,10,100))
        val route=EngineSelection(card.engineId,card.id,directory.resolve("healthy-external").toString())
        val checked=mutableListOf<Path>()
        val controller=DesktopModelController(DesktopModelLibrary(directory),backgroundScope,{fail(it)},verifyModel={_,path ->checked.add(path);route})
        try {
            controller.refresh(PipelineConfig(speech=route,sourceLanguage="en"))
            controller.state.first {!it.refreshing}
            assertEquals(listOf(Path.of(route.modelPath)),checked)
            assertEquals(route,controller.verifiedSelection(card.id))
            assertTrue(controller.state.value.transfers.getValue(card.id).paused)
        } finally {controller.close();directory.toFile().deleteRecursively()}
    }

    @Test fun importingAnotherCopyDuringStartupCannotEraseTheSavedCopyProofOrActivateTheImport() = runTest {
        val directory=Files.createTempDirectory("hearth-refresh-copy-proof-")
        val library=DesktopModelLibrary(directory)
        val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
        val saved=EngineSelection(card.engineId,card.id,directory.resolve("saved-A").toString())
        val imported=saved.copy(modelPath=directory.resolve("imported-B").toString())
        val config=PipelineConfig(speech=saved,sourceLanguage="en")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val controller=DesktopModelController(library,backgroundScope,{fail(it)},verifyModel={_,path ->
            if(path.fileName.toString()=="saved-A") {entered.complete(Unit);release.await();saved} else imported
        })
        try {
            controller.refresh(config);entered.await()
            controller.verify(card.id,Path.of(imported.modelPath))
            controller.state.first {it.transfers[card.id]==null && it.verifiedImports[card.id]==imported}
            release.complete(Unit);controller.state.first {!it.refreshing}
            assertEquals(imported,controller.verifiedSelection(card.id),"The library Use action can still offer the newly imported copy")
            assertEquals(Path.of(saved.modelPath),controller.selectedStatus(card.id,saved.modelPath)?.path)
            assertEquals(true,controller.selectedStatus(card.id,saved.modelPath)?.ready)
            assertTrue(home(config,controller,card).recognitionReady,"The saved copy keeps its own verified proof")
            assertEquals(saved,config.speech,"An independent import must never activate itself")
        } finally {release.complete(Unit);controller.close();directory.toFile().deleteRecursively()}
    }

    @Test fun failedReverificationInvalidatesOnlyThatExactCopyAndSurfacesItsRealError() = runTest {
        val directory=Files.createTempDirectory("hearth-refresh-bad-copy-")
        val library=DesktopModelLibrary(directory)
        val card=library.catalog.first {it.engineId=="qwen3-asr-local"}
        val saved=EngineSelection(card.engineId,card.id,directory.resolve("saved-A").toString())
        val other=saved.copy(modelPath=directory.resolve("bad-B").toString())
        val config=PipelineConfig(speech=saved,sourceLanguage="en")
        var corruptSaved=false
        val controller=DesktopModelController(library,backgroundScope,{},verifyModel={_,path ->
            if(path==Path.of(other.modelPath) || corruptSaved)error("Pinned checksum changed for ${path.fileName}")
            saved
        })
        try {
            controller.refresh(config);controller.state.first {!it.refreshing}
            controller.verify(card.id,Path.of(other.modelPath));controller.state.first {it.transfers[card.id]==null}
            assertEquals(true,controller.selectedStatus(card.id,saved.modelPath)?.ready)
            assertTrue(home(config,controller,card).recognitionReady,"Another copy's corruption must not disqualify the saved healthy copy")
            corruptSaved=true
            controller.verify(card.id,Path.of(saved.modelPath));controller.state.first {it.transfers[card.id]==null}
            val invalid=controller.selectedStatus(card.id,saved.modelPath)
            assertEquals(false,invalid?.ready);assertTrue(invalid?.issue.orEmpty().contains("checksum changed"))
            val failed=home(config,controller,card)
            assertFalse(failed.recognitionReady);assertFalse(failed.setupVerifying)
            assertTrue(failed.setupIssue.orEmpty().contains("checksum changed"))
        } finally {controller.close();directory.toFile().deleteRecursively()}
    }

    private fun home(config:PipelineConfig,controller:DesktopModelController,card:DesktopModelCard)=homeState(
        config,listOf(EngineDescriptor(card.engineId,"Qwen3-ASR",PipelineStage.STT,EngineKind.LOCAL,requiresModel=true)),
        1,1,"Ready",false,catalog=controller.library.catalog,
        installations=(controller.library.installations.value+controller.state.value.verifiedImports.mapValues {DesktopModelStatus(true,Path.of(it.value.modelPath))})+
            listOfNotNull(config.speech,config.translation,config.voice).mapNotNull {route ->controller.selectedStatus(route.modelId,route.modelPath)?.let {route.modelId to it}}.toMap(),
        verifyingSelections=controller.state.value.verifyingSelections,verificationIssues=controller.state.value.issues,
    )
}
