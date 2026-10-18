package com.sal7one.transiber.caption

import android.app.Application
import com.sal7one.transiber.byok.CloudCaptionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise real publication paths without opening capture, native models or provider clients. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CaptionControllerProvenanceTest {
    private fun call(controller: CaptionEngineController, name: String, vararg args: Any?) {
        controller.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(controller, *args)
    }

    private fun withController(
        route: CaptionTranslationRoute,
        engineTarget: Boolean = false,
        action: (CaptionEngineController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CaptionEngineController(RuntimeEnvironment.getApplication(), scope)
        ReflectionHelpers.setField(controller, "currentConfig", CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE, target = TranslationTarget.ARABIC))
        ReflectionHelpers.setField(controller, "activeRoute", route)
        ReflectionHelpers.setField(controller, "engineTranslatesToTarget", engineTarget)
        // No translator is under test: queued second stages must not load a native model.
        ReflectionHelpers.getField<CoroutineScope>(controller, "translationScope").cancel()
        try {
            action(controller)
        } finally {
            controller.shutdown()
            runBlocking { controller.awaitReleased() }
            scope.cancel()
        }
    }

    @Test fun finalPromotionAndAtomicTrackingPreserveRawTextAndItsPublishedRole() {
        val routes = listOf(
            Triple(CaptionTranslationRoute.ORIGINAL, false, CaptionTextRole.SOURCE),
            Triple(CaptionTranslationRoute.TEXT_TRANSLATOR, false, CaptionTextRole.SOURCE),
            Triple(CaptionTranslationRoute.LIVE_TARGET, false, CaptionTextRole.SOURCE),
            Triple(CaptionTranslationRoute.LIVE_TARGET, true, CaptionTextRole.TARGET),
            Triple(CaptionTranslationRoute.ENGLISH_TASK, false, CaptionTextRole.TARGET),
            Triple(CaptionTranslationRoute.ENGLISH_PIVOT, false, CaptionTextRole.INTERMEDIATE),
            Triple(CaptionTranslationRoute.ENGLISH_TEXT, false, CaptionTextRole.SOURCE),
            Triple(CaptionTranslationRoute.UNSUPPORTED, false, CaptionTextRole.SOURCE),
        )
        for ((route, engineTarget, role) in routes) withController(route, engineTarget) { controller ->
            call(controller, "promoteFinal", "settled text", null)
            controller.state.value.history.last().let {
                assertEquals("settled text", it.original)
                assertNull(it.translation)
                assertEquals("$route final", role, it.primaryRole)
            }

            ReflectionHelpers.setField(controller, "lastPartial", "utterance ended")
            call(controller, "promotePartial")
            controller.state.value.let {
                assertEquals("utterance ended", it.history.last().original)
                assertEquals("$route promotion", role, it.history.last().primaryRole)
                assertEquals("", it.partial)
                assertEquals(CaptionTextRole.SOURCE, it.partialRole)
            }

            ReflectionHelpers.setField(controller, "lastPartial", "atomic final")
            call(controller, "promoteAndTrackAtomic", "a new utterance", controller.currentConfig)
            controller.state.value.let {
                assertEquals("atomic final", it.history.last().original)
                assertEquals("$route atomic final", role, it.history.last().primaryRole)
                assertEquals("a new utterance", it.partial)
                assertEquals("$route atomic partial", role, it.partialRole)
            }

            ReflectionHelpers.setField(controller, "displayedPartial", "")
            call(controller, "publishPartial", "current partial", controller.currentConfig)
            assertEquals("current partial", controller.state.value.partial)
            assertEquals("$route partial", role, controller.state.value.partialRole)
        }
    }

    @Test fun publishedTargetAndIntermediateRolesDoNotChangeWithTheNextRoute() {
        for ((route, role) in listOf(CaptionTranslationRoute.ENGLISH_TASK to CaptionTextRole.TARGET,
            CaptionTranslationRoute.ENGLISH_PIVOT to CaptionTextRole.INTERMEDIATE)) {
            withController(route) { controller ->
                call(controller, "promoteFinal", "English engine output", null)
                val published = controller.state.value.history.single()
                ReflectionHelpers.setField(controller, "activeRoute", CaptionTranslationRoute.ORIGINAL)
                assertEquals(role, published.primaryRole)
                assertEquals(role, controller.state.value.history.single().primaryRole)
                call(controller, "promoteFinal", "new source text", null)
                assertEquals(CaptionTextRole.SOURCE, controller.state.value.history.last().primaryRole)
            }
        }
    }

    @Test fun structuredProviderRunsKeepIndependentSourceAndTranslationFields() {
        withController(CaptionTranslationRoute.LIVE_TARGET) { controller ->
            call(controller, "updateCloudCaption", CloudCaptionUpdate(10, 1, "original", "en", false, false))
            call(controller, "updateCloudCaption", CloudCaptionUpdate(11, 1, "ترجمة", "ar", true, false))
            val lines = controller.state.value.history
            assertEquals(2, lines.size)
            assertEquals("original", lines[0].original)
            assertNull(lines[0].translation)
            assertEquals(CaptionTextRole.SOURCE, lines[0].primaryRole)
            assertEquals("", lines[1].original)
            assertEquals("ترجمة", lines[1].translation)
            assertEquals(CaptionTextRole.SOURCE, lines[1].primaryRole)
            assertNotEquals(lines[0].id, lines[1].id)
        }
    }

    @Test fun clearingTargetPartialDropsItsRoleAndTextTogether() {
        withController(CaptionTranslationRoute.ENGLISH_TASK) { controller ->
            call(controller, "publishPartial", "target text", controller.currentConfig)
            assertEquals(CaptionTextRole.TARGET, controller.state.value.partialRole)
            controller.clearTranscript()
            assertEquals("", controller.state.value.partial)
            assertNull(controller.state.value.partialTranslation)
            assertEquals(CaptionTextRole.SOURCE, controller.state.value.partialRole)
            assertTrue(controller.state.value.history.isEmpty())
        }
    }
}
