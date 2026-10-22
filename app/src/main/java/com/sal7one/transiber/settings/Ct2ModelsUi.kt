package com.sal7one.transiber.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.Ct2ModelSpec
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.models.rememberModelBrowseString
import com.sal7one.transiber.ui.components.ModelFilterPicker
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.ui.components.ModelSourceLinks
import com.sal7one.transiber.ui.components.ModelLibraryDisclosure
import com.sal7one.transiber.translation.ConversationTranslationSettings
import kotlinx.coroutines.*

internal fun ct2BrowserMatches(spec: Ct2ModelSpec,query: String,source: String,target: String,kind: String,
    hardware: com.sal7one.transiber.models.ModelHardwareFilter = com.sal7one.transiber.models.ModelHardwareFilter.ALL): Boolean =
    hardware.matches(emptySet()) && kind!="text" && (source=="any" || source in spec.sources) && (target=="any" || target in spec.targets) &&
        (source=="any" || target=="any" || spec.supports(source,target)) &&
        ModelSearch.matches(query,spec.id,spec.label,spec.precisionLabel,"CTranslate2 CT2 CPU compact Arabic English",spec.license,
            if (spec.tokenizerMode>=2) "Jisr Saudi Egyptian Moroccan Algerian Tunisian Libyan Lebanese Syrian Palestinian Gulf Sudanese Yemeni Iraqi السعودية مصري دارجة شامي خليجي" else "",
            ModelSearch.languageTerms(spec.sources+spec.targets))

/** Compact packages use one card per directional model, independent of GGUF backend settings. */
@Composable
internal fun Ct2ModelsUi(query: String,source: String,target: String,kind: String,installedOnly: Boolean,
    selectedId: String,onSelect: (String) -> Unit,onError: (String) -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val text=rememberUiText()
    val downloads=remember { FileDownloads(context) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(emptyList<com.sal7one.transiber.downloads.FileDownload>()) }
    var importedVariant by remember { mutableStateOf<Ct2ModelSpec?>(null) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            busy=true
            try {
                val result=withContext(Dispatchers.IO) {
                    val job=currentCoroutineContext()
                    val result=context.contentResolver.openInputStream(uri)?.use { Ct2Package.importZip(context,it) { job.ensureActive() } }
                        ?: error("Cannot open CT2 translation package")
                    com.sal7one.transiber.preferences.ModelSourceBindings.recordImported(context,result.first.id,uri,listOf(result.second))
                    result
                }
                importedVariant=result.first
                revision++ // Installation does not replace the active translation stage.
            } catch(e: CancellationException) { throw e } catch(e: Exception) { onError(generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() }) }
            finally { busy=false }
        }
    }
    LaunchedEffect(Unit) {
        var lastError: String? = null
        while(isActive) {
            try {
                records=withContext(Dispatchers.IO) { if(ByokPolicy.FEATURE_BYOK)downloads.list() else emptyList() }
                lastError=null
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val message=e.message ?: e.toString()
                if(message!=lastError)onError(message)
                lastError=message
            }
            revision++;delay(1500)
        }
    }
    Ct2Catalog.models.filter { ct2BrowserMatches(it,query,source,target,kind) }
        .groupBy { it.groupId }.forEach { (groupId, allVariants) ->
        val variants=remember(revision,allVariants,installedOnly) { allVariants.filter { !installedOnly || Ct2Package.installed(context,it) } }
        if(variants.isEmpty()) return@forEach
        val initialVariant=selectedId.takeIf { selected -> variants.any { it.id==selected } }
            ?: variants.filter { Ct2Package.installed(context,it) }.minByOrNull { it.bytes }?.id
            ?: variants.minBy { it.bytes }.id
        var variantId by rememberModelBrowseString("ct2-variant-$groupId", initialVariant)
        LaunchedEffect(importedVariant?.id, groupId) {
            importedVariant?.takeIf { it.groupId==groupId }?.let { variantId=it.id }
        }
        val spec=variants.firstOrNull { it.id==variantId } ?: variants.first()
        val ready=remember(revision,spec) { Ct2Package.installed(context,spec) }
        if(!installedOnly || ready) {
            val parts=Ct2Package.parts(spec)
            val status=translationDownloadState(parts.map { it.id }.toSet(),records)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(SettingsLayout.cardPadding),verticalArrangement=Arrangement.spacedBy(SettingsLayout.contentGap)) {
                    Text(spec.label,style=MaterialTheme.typography.titleMedium)
                    Text(android.text.format.Formatter.formatShortFileSize(context,spec.bytes) + " · CPU" + spec.precisionLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    if(!Ct2Package.runtimeSupported()) Text(text(UiR.string.ct2_runtime_hint),style=MaterialTheme.typography.bodySmall)
                    Text(text(UiR.string.ct2_experimental),style=MaterialTheme.typography.bodySmall)
                    if (!ready && !spec.downloadEnabled) Text(text(UiR.string.jisr_import_hint),style=MaterialTheme.typography.bodySmall)
                    if(ready) Text(text(if(selectedId==spec.id) UiR.string.model_browser_selected else UiR.string.model_download_installed))
                    else if(status.total>0 && status.completed>0) Text(text(UiR.string.model_files_downloaded,status.completed,status.total))
                    status.failure?.let { Text(it.error, color=MaterialTheme.colorScheme.error) }
                    if(status.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!ready || selectedId!=spec.id) Button(modifier=Modifier.heightIn(min=SettingsLayout.touchTarget),enabled=Ct2Package.runtimeSupported() && !busy && !status.active,onClick={
                        if(!ready && (!ByokPolicy.FEATURE_BYOK || !spec.downloadEnabled)) importer.launch(arrayOf("application/zip","application/octet-stream"))
                        else scope.launch {
                            busy=true
                            try {
                                withContext(Dispatchers.IO) {
                                    if(ready) spec.verify(Ct2Package.directory(context,spec))
                                    else Ct2Package.enqueue(context,downloads,spec)
                                }
                                if(ready)onSelect(spec.id)
                            } catch(e: CancellationException) { throw e } catch(e: Exception) { onError(generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() }) }
                            finally { busy=false }
                        }
                    }) { Text(text(if(ready) UiR.string.translation_browser_use else if(ByokPolicy.FEATURE_BYOK && spec.downloadEnabled) UiR.string.model_browser_download_install else UiR.string.translation_browser_import)) }
                    var details by rememberSaveable(spec.groupId) { mutableStateOf(false) }
                    ModelLibraryDisclosure(text(UiR.string.model_browser_details),details,{details=!details}) {
                        if (variants.size>1) ModelFilterPicker(text(UiR.string.model_browser_precision),spec.id,
                            variants.map { it.id to it.precisionLabel },{variantId=it},enabled=!busy)
                        ModelSourceLinks(spec.modelCard,"https://huggingface.co/${spec.files.first().repo}/tree/${spec.files.first().revision}",spec.modelCard,onError=onError)
                        Text(text(UiR.string.ct2_files_details,spec.license,spec.files.size),style=MaterialTheme.typography.bodySmall)
                        if(spec.tokenizerMode==0)Text(text(UiR.string.ct2_promoted_weights),style=MaterialTheme.typography.bodySmall)
                        Text(text(UiR.string.ct2_package_details,spec.id),style=MaterialTheme.typography.bodySmall)
                        if (spec.tokenizerMode==2 || spec.tokenizerMode==4) {
                            var dialect by remember { mutableStateOf(ConversationTranslationSettings.jisrDialect(context)) }
                            JisrDialectPicker(dialect, !busy) { value ->
                                try { ConversationTranslationSettings.setJisrDialect(context,value);dialect=value }
                                catch (e: Exception) { onError(e.message ?: e.toString()) }
                            }
                        }
                        if(ready || (ByokPolicy.FEATURE_BYOK && spec.downloadEnabled)) {
                            OutlinedButton(enabled=Ct2Package.runtimeSupported() && !busy,onClick={importer.launch(arrayOf("application/zip","application/octet-stream"))}) { Text(text(UiR.string.translation_browser_import)) }
                        }
                    }
                }
            }
        }
    }
}
