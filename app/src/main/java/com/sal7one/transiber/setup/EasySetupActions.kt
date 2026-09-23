package com.sal7one.transiber.setup

import android.content.Context
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.reading.ReadingOverlayService
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.shortcuts.checkSpeechAssetPresence
import com.sal7one.transiber.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class EasyLocalStatus(val speech: LocalSpeechModel?, val translation: Boolean, val downloads: List<FileDownload>) {
    val ready get() = speech!=null && translation
}
internal class EasySetupActions(context: Context) {
    private val context=context.applicationContext
    private val speechStore=LocalSpeechModels(File(context.filesDir,"speech-models"))
    private val translations=LocalTranslationModels(File(context.filesDir,"translation-models"))
    val speechDownload get() = checkNotNull(SpeechDownloads.find(EasySetupPreset.speech.id))
    suspend fun localStatus(translatorId: String): EasyLocalStatus = withContext(Dispatchers.IO) {
        val speech=speechStore.list().firstOrNull { it.profile==EasySetupPreset.speech }
        speech?.let { checkSpeechAssetPresence(it.root) }
        val translation=MarianPackage.find(translatorId)?.let { MarianPackage.installed(context,it)!=null }
            ?: MarianCascade.find(translatorId)?.let { MarianCascade.installed(context,it) }
            ?: translations.installed().any { it.id==translatorId }
        EasyLocalStatus(speech,translation,
            if(ByokPolicy.FEATURE_BYOK) FileDownloads(context).list() else emptyList())
    }
    private fun idle() {
        check(!CaptionCaptureService.running.value && !ReadingOverlayService.running.value && LocalWorkGate.owner.value==null) {
            "Stop the current session before changing setup."
        }
    }
    suspend fun downloadMissing(translatorId: String) = withContext(Dispatchers.IO) {
        check(ByokPolicy.FEATURE_BYOK) { "Import models in the offline build." };idle()
        val state=localStatus(translatorId);val downloads=FileDownloads(context)
        fun enqueue(id: String, url: String, name: String) {
            val record=state.downloads.firstOrNull {it.title==name}
            if(record?.active==true)return
            if(record?.failed==true && record.id<0)downloads.retry(record.id)
            else downloads.enqueue(DownloadSpec.parse(url,name),true,id)
        }
        if(state.speech==null)enqueue(speechDownload.profile.id,speechDownload.url,speechDownload.fileName)
        if(!state.translation) {
            val pair=MarianPackage.find(translatorId)
            if(pair!=null)MarianPackage.enqueue(context,downloads,pair)
            else if(MarianCascade.find(translatorId)!=null)MarianCascade.enqueue(context,downloads,checkNotNull(MarianCascade.find(translatorId)))
            else TranslationCatalog.find(translatorId).let {enqueue(it.id,it.url,it.fileName)}
        }
    }
    suspend fun applyLocal(translatorId: String, source: String, target: String) {
        idle();val status=localStatus(translatorId);check(status.ready) { "Wait for both models to install." }
        CaptionConfigStore.update(context) { EasySetupPreset.local(it,checkNotNull(status.speech).id,translatorId,source,target) }
        ConversationTranslationSettings.useEverywhere(context,"local",translatorId)
        com.sal7one.transiber.ocr.OcrPreferences(context).update {it.copy(translate=true)}
    }
    suspend fun applyCloud(provider: CloudConfigStore.Provider, baseUrl: String, model: String, key: String, target: String) {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud connections are unavailable in the offline build." };idle()
        EasySetupPreset.cloud(CaptionOverlayConfig(),provider,target) // Validate before saving credentials.
        val endpoint=EasySetupPreset.endpoint(baseUrl)
        require(model.isNotBlank()) { "Enter the speech model name supplied by your server." }
        val reuse=EasySetupPreset.canReuseKey(provider,endpoint,CloudConfigStore.provider(context),CloudConfigStore.baseUrl(context))
        val mode=if(provider==CloudConfigStore.Provider.OPENAI)CloudConfigStore.SttMode.STREAMING_OPENAI else CloudConfigStore.SttMode.BATCH
        val stored=if(reuse && key.isBlank())ApiKeyStore.hasOpenAiKey(context).also {
            if(!it)ApiKeyStore.lastFailure?.let {failure->error(failure)}
        } else false
        require(!cloudSpeechKeyRequired(mode,provider) || key.isNotBlank() || stored) { "Add an API key for ${provider.label}." }
        // Never transfer a saved provider key to a different endpoint.
        if(key.isNotBlank() || !reuse)check(ApiKeyStore.setOpenAiKey(context,key)) { ApiKeyStore.lastFailure ?: "Could not save the API key." }
        CloudConfigStore.configureSpeech(context,provider,endpoint,model,mode)
        CaptionConfigStore.update(context) { EasySetupPreset.cloud(it,provider,target) }
    }
}
