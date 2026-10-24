package com.sal7one.transiber.translation

import android.app.Application
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TranslationConnectionSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val provider = TextTranslationProvider.LIBRETRANSLATE
    private val original = CloudTranslationLanguages(mapOf("en" to "en"), mapOf("ar" to "ar"), mapOf("en" to setOf("ar")))
    private val replacement = CloudTranslationLanguages(mapOf("ru" to "ru"), mapOf("en" to "en"), mapOf("ru" to setOf("en")))
    private val oldEndpoint = "https://old.example/api"
    private val newEndpoint = "https://new.example/api"

    @Before fun reset() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        UserPreferences.initialize(context)
        assertTrue(UserPreferences.store(context, "conversation-translators").edit().clear().commit())
    }

    private suspend fun seed() {
        ConversationTranslationSettings.checkAndSave(context, provider, oldEndpoint, "", "") { original }
        ConversationTranslationSettings.select(context, provider.id)
    }

    @Test fun failedRefreshAndReplacementPreserveWorkingConnectionAndSelection() = runBlocking {
        seed()
        for (endpoint in listOf(oldEndpoint, newEndpoint)) {
            val failure = runCatching {
                ConversationTranslationSettings.checkAndSave(context, provider, endpoint, "", "") { next ->
                    assertEquals(endpoint, next.endpoint)
                    // While the network check is in progress, active sessions still see the old route.
                    assertEquals(oldEndpoint, ConversationTranslationSettings.connection(context, provider).endpoint)
                    assertEquals(original, ConversationTranslationSettings.capabilities(context, provider))
                    throw IOException("Server unavailable")
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(oldEndpoint, ConversationTranslationSettings.endpoint(context, provider))
            assertEquals(original, ConversationTranslationSettings.capabilities(context, provider))
            assertEquals(provider.id, ConversationTranslationSettings.selected(context))
        }
    }

    @Test fun cancelledCheckDoesNotPublishDraft() = runBlocking {
        seed()
        val entered = CompletableDeferred<Unit>()
        val pending = launch {
            ConversationTranslationSettings.checkAndSave(context, provider, newEndpoint, "", "") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(oldEndpoint, ConversationTranslationSettings.endpoint(context, provider))
        assertEquals(original, ConversationTranslationSettings.capabilities(context, provider))
    }

    @Test fun successfulCheckPublishesEndpointAndCapabilitiesTogether() = runBlocking {
        seed()
        val found = ConversationTranslationSettings.checkAndSave(context, provider, newEndpoint, "west", "") {
            assertEquals(oldEndpoint, ConversationTranslationSettings.connection(context, provider).endpoint)
            assertEquals(original, ConversationTranslationSettings.capabilities(context, provider))
            replacement
        }
        assertEquals(replacement, found)
        assertEquals(newEndpoint, ConversationTranslationSettings.connection(context, provider).endpoint)
        assertEquals("west", ConversationTranslationSettings.region(context, provider))
        assertEquals(replacement, ConversationTranslationSettings.capabilities(context, provider))
        assertEquals(provider.id, ConversationTranslationSettings.selected(context))
    }

    @Test fun staleCheckCannotOverwriteNewerSuccessfulConnection() = runBlocking {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stale = async {
            runCatching {
                ConversationTranslationSettings.checkAndSave(context, provider, "https://stale.example", "", "") {
                    entered.complete(Unit)
                    release.await()
                    original
                }
            }
        }
        entered.await()
        ConversationTranslationSettings.checkAndSave(context, provider, newEndpoint, "", "") { replacement }
        release.complete(Unit)
        assertTrue(stale.await().exceptionOrNull()?.message.orEmpty().contains("changed during language discovery"))
        assertEquals(newEndpoint, ConversationTranslationSettings.endpoint(context, provider))
        assertEquals(replacement, ConversationTranslationSettings.capabilities(context, provider))
    }

    @Test fun removingConnectionInvalidatesInFlightFirstSetup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            runCatching {
                ConversationTranslationSettings.checkAndSave(context, provider, newEndpoint, "", "") {
                    entered.complete(Unit)
                    release.await()
                    replacement
                }
            }
        }
        entered.await()
        ConversationTranslationSettings.forget(context, provider)
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull()?.message.orEmpty().contains("changed during language discovery"))
        assertNull(ConversationTranslationSettings.capabilities(context, provider))
    }
}
