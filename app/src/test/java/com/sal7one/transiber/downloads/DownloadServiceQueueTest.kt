package com.sal7one.transiber.downloads

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.translation.Ct2Package
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DownloadServiceQueueTest {
    @Test @Config(sdk=[35]) fun androidBackgroundDeadlinePausesDurableWorkAndStopsTheService() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val app = RuntimeEnvironment.getApplication()
        val preferences=app.getSharedPreferences("file_downloads",Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val controller=Robolectric.buildService(workerType()).create()
        try {
            val record=JSONObject().put("id",-902L).put("title","Long download").put("phase","Waiting")
            preferences.edit().putString("download_-902",record.toString()).putStringSet("public_ids",setOf("-902")).commit()
            val service=controller.get()
            service.javaClass.getMethod("onTimeout",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(service,1,1)
            assertEquals("Paused",JSONObject(preferences.getString("download_-902","{}")!!).getString("phase"))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
            val notification=Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(303)
            assertTrue(notification.extras.getCharSequence("android.text").toString().contains("resume saved files"))
        } finally {controller.destroy()}
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
    }
    private fun workerType() = Class.forName("com.sal7one.transiber.downloads.DownloadService").asSubclass(android.app.Service::class.java)
    @Suppress("UNCHECKED_CAST")
    @Test fun drainDoesNotParkWhenWorkArrivesWithoutAnotherServiceStartEvent() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("x86_64"))
        ShadowVMRuntime.setIs64Bit(true)
        val type = workerType()
        val controller = Robolectric.buildService(type).create()
        val jobs = type.getDeclaredField("jobs").apply { isAccessible = true }.get(null) as ConcurrentHashMap<Long, Job>
        val events = type.getDeclaredField("queueEvents").apply { isAccessible = true }.get(null) as Channel<Unit>
        val blocker = Job()
        try {
            jobs[-900] = blocker
            controller.get().onStartCommand(null, 0, 1)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // A caller journals a new request before Android delivers its start command.
            // The unsupported runtime fails before URI/network access, keeping this test local.
            val part = Ct2Package.parts(Ct2Catalog.models.first()).first()
            val record = JSONObject().put("id", -901L).put("title", "fixture").put("model", part.id)
                .put("phase", "Waiting").put("modelsFolder", true).put("downloadComplete", true)
            preferences.edit().putString("download_-901", record.toString()).putStringSet("public_ids", setOf("-901")).commit()
            jobs.remove(-900); blocker.cancel()
            events.trySend(Unit)
            until { JSONObject(preferences.getString("download_-901", "{}")!!).optString("phase") == "Failed" }
            assertTrue(JSONObject(preferences.getString("download_-901", "{}")!!).getString("error").contains("ARM64 runtime"))
        } finally { jobs.remove(-900)?.cancel(); jobs.remove(-901)?.cancel(); controller.destroy() }
    }

    @Test fun failedPauseJournalIsReportedWithoutCrashingTheService() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val app = RuntimeEnvironment.getApplication()
        val original = app.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
        original.edit().clear().commit()
        val controller = Robolectric.buildService(workerType()).create()
        try {
            val service = controller.get()
            val downloads = service.javaClass.getDeclaredField("downloads").apply { isAccessible = true }.get(service) as FileDownloads
            val failing = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                if (method.name != "edit") method.invoke(original, *(args ?: emptyArray()))
                else {
                    val editor = original.edit()
                    var wrapper: SharedPreferences.Editor? = null
                    wrapper = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, values ->
                        if (editMethod.name == "commit") false
                        else editMethod.invoke(editor, *(values ?: emptyArray())).let { if (it is SharedPreferences.Editor) wrapper else it }
                    } as SharedPreferences.Editor
                    wrapper
                }
            } as SharedPreferences
            FileDownloads::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(downloads, failing)
            service.onStartCommand(Intent(app, service.javaClass).setAction("com.sal7one.transiber.downloads.PAUSE_ALL"), 0, 1)
            val manager = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
            until { manager.getNotification(303) != null }
            assertTrue(manager.getNotification(303).extras.getCharSequence("android.text").toString().contains("Cannot pause download queue"))
        } finally { controller.destroy() }
    }
}
