package com.sal7one.transiber.benchmark

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.i18n.languageLabel
import com.sal7one.transiber.i18n.rememberUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Inspect the same publisher inputs used for comparisons, with explicitly requested playback. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkSamplePreview(suite: BenchmarkSuite, task: BenchmarkTask, onDismiss: () -> Unit) {
    val uiText = rememberUiText()
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var savedSource by rememberBenchmarkString("sample-preview-source", "en")
    var savedTarget by rememberBenchmarkString("sample-preview-target", "ar")
    val source = savedSource.takeIf { it in BenchmarkLanguageMatrix.languages } ?: "en"
    val targets = remember(suite, source) { suite.cases.filter { !it.speech && it.source == source }
        .map { it.target }.distinct() }
    val target = savedTarget.takeIf { it in targets } ?: targets.firstOrNull().orEmpty()
    val samples = remember(suite, task, source, target) {
        if (task == BenchmarkTask.VOICE) emptyList() else suite.selected(task == BenchmarkTask.SPEECH,
            source, if (task == BenchmarkTask.SPEECH) "" else target, full = false).filter { it.silenceMs == 0 }
    }
    var playback by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun stop() { playback?.cancel() }
    fun dismiss() { stop(); onDismiss() }
    fun play(sample: BenchmarkCase) {
        if (playing == sample.id) { stop(); return }
        val previous = playback
        previous?.cancel()
        playback = scope.launch {
            previous?.join()
            playing = sample.id
            error = null
            try {
                withContext(Dispatchers.IO) {
                    // The suite validates original WAV bytes and normalized PCM before speaker output.
                    val clip = suite.audio(context, sample)
                    val samples = BenchmarkAudio.normalizeForComparison(clip.samples)
                    coroutineContext.ensureActive()
                    var track: AudioTrack? = null
                    try {
                        val output = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2)
                            .build().also { track = it }
                        check(output.state != AudioTrack.STATE_UNINITIALIZED) { "Cannot initialize benchmark recording playback" }
                        check(output.write(samples, 0, samples.size) == samples.size) { "Cannot load the complete benchmark recording for playback" }
                        check(output.state == AudioTrack.STATE_INITIALIZED) { "Cannot prepare benchmark recording playback" }
                        coroutineContext.ensureActive()
                        output.play()
                        delay(clip.durationMs + 100)
                    } finally {
                        track?.let { output ->
                            try { if (output.playState != AudioTrack.PLAYSTATE_STOPPED) output.stop() }
                            finally { output.release() }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.toString() }
            finally { playing = null }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(task, source, target) { stop(); error = null; listState.scrollToItem(0) }
    AlertDialog(onDismissRequest = ::dismiss,
        title = { Text(uiText.bench("Reference samples", "عينات المقارنة", "参考样本")) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    SelectionContainer {
                        Text("${suite.title}\n${suite.license}\n${suite.sourceUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(uiText.bench("Bundled publisher audio and text; no model is needed to preview them.",
                        "الصوت والنصوص المرفقة من الناشر؛ لا تحتاج إلى نموذج لعرضها.",
                        "内置的发布者音频与文本；预览无需模型。"), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BenchmarkLanguageMatrix.languages.forEach { code ->
                            FilterChip(source == code, { savedSource = code },
                                label = { Text(uiText.languageLabel(LanguageCatalog.option(code))) })
                        }
                    }
                    if (task == BenchmarkTask.TRANSLATION) {
                        Text(uiText.bench("Reference translation to", "الترجمة المرجعية إلى", "参考译文语言"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            targets.forEach { code -> FilterChip(target == code, { savedTarget = code },
                                label = { Text(uiText.languageLabel(LanguageCatalog.option(code))) }) }
                        }
                    }
                    if (source == "ar") Text(uiText.bench(
                        "FLEURS Arabic recordings use Egyptian Arabic (ar_eg). Your Saudi recordings remain a separate input.",
                        "تسجيلات FLEURS العربية باللهجة المصرية (ar_eg). تسجيلاتك السعودية تبقى مدخلاً منفصلاً.",
                        "FLEURS 阿拉伯语录音使用埃及阿拉伯语（ar_eg）。个人沙特录音仍为独立输入。"),
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)) } }
                    if (samples.isEmpty()) Text(uiText.bench("No bundled reference samples for this selection.",
                        "لا توجد عينات مرجعية مرفقة لهذا الاختيار.", "此选项没有内置参考样本。"))
                }
                items(samples, key = { it.id }) { sample ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sample.id, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (task == BenchmarkTask.TRANSLATION) {
                                        Text(uiText.bench("Source text", "النص الأصلي", "源文本"), style = MaterialTheme.typography.labelLarge)
                                        Text(sample.text)
                                        Text(uiText.bench("Publisher translation", "ترجمة الناشر", "发布者译文"), style = MaterialTheme.typography.labelLarge)
                                    } else Text(uiText.bench("Publisher transcript", "تفريغ الناشر", "发布者转写文本"),
                                        style = MaterialTheme.typography.labelLarge)
                                    Text(sample.reference.orEmpty())
                                }
                            }
                            if (task == BenchmarkTask.SPEECH && sample.audio != null) TextButton({ play(sample) }) {
                                Text(if (playing == sample.id) uiText.bench("Stop audio", "إيقاف الصوت", "停止音频")
                                    else uiText.bench("Play recording", "تشغيل التسجيل", "播放录音"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(::dismiss) { Text(uiText.bench("Close", "إغلاق", "关闭")) } },
        dismissButton = { if (playing != null) TextButton(::stop) { Text(uiText.bench("Stop audio", "إيقاف الصوت", "停止音频")) } })
}
