package com.sal7one.transiber.ocr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.byok.ByokPolicy

/** One observable choice shared by Camera, reading setup and the reading service. */
internal class OcrPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("camera-translate",0)
    fun read(): OcrSelection = OcrSelection(
        profileId=prefs.getString("profile","latin")!!,
        source=prefs.getString("source","en")!!, target=prefs.getString("target","ar")!!,
        providerId=if(ByokPolicy.FEATURE_BYOK) prefs.getString("provider","local")!! else "local",
        translate=prefs.getBoolean("translate",true),
        localModelId=prefs.getString("local-model",null),
    )
    fun update(change: (OcrSelection) -> OcrSelection) {
        val next=change(read())
        prefs.edit().putString("profile",next.profileId).putString("source",next.source)
            .putString("target",next.target).putString("provider",next.providerId)
            .putBoolean("translate",next.translate).putString("local-model",next.localModelId).apply()
    }
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

@Composable internal fun rememberOcrPreferences(): Pair<OcrSelection,OcrPreferences> {
    val context=LocalContext.current
    val store=remember(context) { OcrPreferences(context) }
    var selection by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val unsubscribe=store.observe { selection=store.read() }
        selection=store.read()
        onDispose { unsubscribe() }
    }
    return selection to store
}
