package com.sal7one.transiber.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Bounded pages avoid composing hundreds of cards inside the screen's scrolling list. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkResultsTable(
    leaders: BenchmarkLeaders?,
    source: String,
    target: String,
    sort: BenchmarkTableSort,
    onSort: (BenchmarkTableSort) -> Unit,
    results: List<BenchmarkResult> = leaders?.candidates.orEmpty(),
    onExport: ((List<BenchmarkResult>, BenchmarkExportFormat) -> Unit)? = null,
    task: String = if (target.isBlank()) "speech" else "translation",
    showLanguages: Boolean = false,
) {
    val uiText = rememberUiText()
    var query by rememberBenchmarkString("results-query", "")
    var status by rememberBenchmarkEnum("results-status", BenchmarkResultStatus.ALL)
    var allInputs by rememberBenchmarkBoolean("results-all-inputs", false)
    var allLanguages by rememberBenchmarkBoolean("results-all-languages", showLanguages)
    var actualGpuOnly by rememberBenchmarkBoolean("results-gpu-only", false)
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<BenchmarkResult?>(null) }
    var chosenCohort by remember { mutableStateOf<BenchmarkCohort?>(null) }
    val currentTaskRows = remember(results, source, target, task) {
        results.filter { it.source == source && it.target == target && it.task == task }
    }
    val latest = remember(currentTaskRows) { currentTaskRows.maxByOrNull { it.timestamp }?.let(BenchmarkTableModel::cohort) }
    val activeCohort = chosenCohort?.takeIf { it.task == task && it.source == source && it.target == target } ?: latest
    LaunchedEffect(showLanguages) { if (showLanguages) { allLanguages = true; allInputs = false } }
    val rows = remember(results, task, source, target, query, status, allInputs, allLanguages, actualGpuOnly, activeCohort, sort) {
        BenchmarkTableModel.sorted(BenchmarkTableModel.filtered(
            BenchmarkTableModel.scopedRows(results, task, source, target, allInputs, allLanguages, activeCohort), query, status,
        ).filter { !actualGpuOnly || it.actualAcceleration?.lowercase(Locale.ROOT) in setOf("vulkan", "opencl", "metal", "cuda") }, sort)
    }
    LaunchedEffect(query, status, allInputs, allLanguages, actualGpuOnly, activeCohort, sort) { page = 0 }
    val pageSize = 25
    val pages = ((rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = page.coerceIn(0, pages - 1)
    val visible = rows.drop(safePage * pageSize).take(pageSize)
    val oneCohort = rows.map(BenchmarkTableModel::cohort).distinct().size <= 1
    // Awards are valid only inside the exact input/device/protocol cohort that produced them.
    fun winner(row: BenchmarkResult, candidate: BenchmarkResult?) = candidate != null &&
        row.runId == candidate.runId && row.identity == candidate.identity && oneCohort

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiText.bench("Your phone's comparison table", "جدول المقارنة على هاتفك", "此设备的模型对比表"),
                style = MaterialTheme.typography.titleLarge)
            Text(uiText.bench(
                "Search every saved model and backend. Compare identical inputs for a fair result; export the visible selection to a spreadsheet.",
                "ابحث في النماذج والمسارات المحفوظة. قارن نفس المدخلات لنتيجة عادلة، وصدّر النتائج الظاهرة إلى جدول.",
                "搜索已保存的模型及后端；相同输入才能公平比较。可将筛选结果导出到电子表格。"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(uiText.bench("Search models, backends, phones or errors", "ابحث بالنموذج أو المسار أو الهاتف أو الخطأ", "搜索模型、后端、设备或错误")) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(!allInputs && !allLanguages, { allInputs = false; allLanguages = false }, label = {
                    Text(uiText.bench("Same input · fair comparison", "نفس المدخلات · مقارنة عادلة", "相同输入 · 公平对比")) })
                FilterChip(allLanguages && !allInputs, { allLanguages = true; allInputs = false },
                    label = { Text(uiText(R.string.benchmark_score_all_languages)) })
                FilterChip(allInputs, { allInputs = true; allLanguages = false }, label = { Text(uiText.bench("All saved results", "كل النتائج المحفوظة", "所有已保存结果")) })
                FilterChip(actualGpuOnly, { actualGpuOnly = !actualGpuOnly }, label = {
                    Text(uiText.bench("Confirmed GPU only", "GPU مؤكد فقط", "仅已确认的 GPU")) })
            }
            if (!allInputs && !allLanguages && activeCohort != null) {
                Text("${activeCohort.device}\n${source.uppercase(Locale.ROOT)}${if (target.isNotBlank()) " → ${target.uppercase(Locale.ROOT)}" else ""} · ${uiText.bench("input", "المدخل", "输入")} ${activeCohort.inputHash.take(12)} · v${activeCohort.protocolVersion}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chosenCohort != null) TextButton({ chosenCohort = null }) {
                    Text(uiText.bench("Return to latest input", "العودة إلى أحدث مدخل", "返回最新输入"))
                }
            }
            if (!oneCohort) Text(uiText.bench(
                "These rows use different inputs, devices or protocols. Times and reference scores are not a shared ranking. Open a row to compare its input.",
                "هذه النتائج تستخدم مدخلات أو أجهزة أو بروتوكولات مختلفة ولا تمثل ترتيبا مشتركا. افتح نتيجة لمقارنة مدخلاتها.",
                "这些结果的输入、设备或协议不同，不能直接排名。打开某行以比较相同输入。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BenchmarkResultStatus.entries.forEach { choice ->
                    FilterChip(status == choice, { status = choice }, label = { Text(when (choice) {
                        BenchmarkResultStatus.ALL -> uiText.bench("All statuses", "كل الحالات", "所有状态")
                        BenchmarkResultStatus.COMPLETED -> uiText.bench("Completed", "مكتمل", "已完成")
                        BenchmarkResultStatus.DISQUALIFIED -> uiText.bench("Disqualified", "مستبعد", "已取消资格")
                        BenchmarkResultStatus.FAILED -> uiText.bench("Failed", "فشل", "失败")
                        BenchmarkResultStatus.EMPTY -> uiText.bench("Empty output", "مخرجات فارغة", "空输出")
                    }) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BenchmarkTableSort.entries.forEach { choice ->
                    FilterChip(sort == choice, { onSort(choice) }, label = { Text(when (choice) {
                        BenchmarkTableSort.WARM -> uiText(R.string.benchmark_table_sort_warm)
                        BenchmarkTableSort.AUDIO_SPEED -> uiText.bench("Audio throughput", "سرعة معالجة الصوت", "音频吞吐量")
                        BenchmarkTableSort.LOAD -> uiText(R.string.benchmark_table_sort_load)
                        BenchmarkTableSort.QUALITY -> uiText(R.string.benchmark_score_sort)
                        BenchmarkTableSort.FIRST_TEXT -> uiText.bench("First output", "أول مخرج", "首个输出")
                        BenchmarkTableSort.SIZE -> uiText.bench("Model size", "حجم النموذج", "模型大小")
                        BenchmarkTableSort.MODEL -> uiText.bench("Model name", "اسم النموذج", "模型名称")
                        BenchmarkTableSort.RECENT -> uiText(R.string.benchmark_table_sort_recent)
                    }) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText.bench("${rows.size} results", "${rows.size} نتيجة", "${rows.size} 条结果"), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                if (onExport != null) BenchmarkExportFormat.entries.forEach { format ->
                    TextButton({ onExport(rows, format) }, enabled = rows.isNotEmpty()) {
                        Text("${uiText.bench("Export", "تصدير", "导出")} ${format.name}")
                    }
                }
            }
            if (rows.isEmpty()) Text(uiText.bench("No saved results match these filters. Run a comparison or change the filters.",
                "لا توجد نتائج تطابق عوامل التصفية. شغّل مقارنة أو غيّر التصفية.", "没有符合筛选条件的已保存结果。请运行对比或修改筛选条件。"))
            else {
                Text(uiText.bench("Swipe the table sideways for all measurements. Tap Details for runtime evidence and output.",
                    "مرر الجدول أفقيا لكل القياسات. افتح التفاصيل لمعلومات التنفيذ والمخرجات.", "左右滑动以查看所有测量值。点击详情查看运行时证据和输出。"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TableCell(uiText(R.string.benchmark_table_model), 220.dp, true)
                        TableCell(uiText(R.string.benchmark_score_language), 100.dp, true)
                        TableCell(uiText(R.string.benchmark_score_heading), 100.dp, true)
                        TableCell(uiText.bench("Requested / actual", "المطلوب / الفعلي", "请求 / 实际"), 140.dp, true)
                        TableCell(uiText.bench("Warm total", "المجموع الدافئ", "预热后总耗时"), 100.dp, true)
                        TableCell(uiText.bench("Audio speed / each", "سرعة الصوت / للجملة", "音频速度 / 每句"), 120.dp, true)
                        TableCell(uiText(R.string.benchmark_table_load), 90.dp, true)
                        TableCell(uiText.bench("First output", "أول مخرج", "首个输出"), 100.dp, true)
                        TableCell(uiText(R.string.benchmark_table_reference), 120.dp, true)
                        TableCell(uiText.bench("Disk size", "حجم الملفات", "磁盘大小"), 90.dp, true)
                        TableCell(uiText.bench("Status", "الحالة", "状态"), 120.dp, true)
                    }
                    HorizontalDivider()
                    visible.forEachIndexed { index, result ->
                        val fastest = winner(result, leaders?.fastest)
                        val accurate = winner(result, leaders?.mostAccurate)
                        val balanced = winner(result, leaders?.balanced)
                        Row(Modifier.background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f) else Color.Transparent,
                            MaterialTheme.shapes.small).padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(220.dp)) {
                                Text(result.model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${result.task} · ${result.source}${if (result.target.isBlank()) "" else " → ${result.target}"}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(recognitionLabel(result), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val awards = listOfNotNull(uiText(R.string.benchmark_table_fastest).takeIf { fastest },
                                    uiText(R.string.benchmark_table_best_reference).takeIf { accurate },
                                    uiText(R.string.benchmark_table_balanced).takeIf { balanced })
                                if (awards.isNotEmpty()) Text(awards.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                TextButton({ detail = result }) { Text(uiText.bench("Details", "التفاصيل", "详情")) }
                            }
                            TableCell("${result.source.uppercase(Locale.ROOT)}${if (result.target.isBlank()) "" else " → ${result.target.uppercase(Locale.ROOT)}"}", 100.dp)
                            TableCell(BenchmarkTableModel.referenceScore100(result)?.let {
                                String.format(Locale.getDefault(), "%.1f / 100", it)
                            } ?: "—", 100.dp)
                            TableCell("${BenchmarkTableModel.requestedBackend(result)?.uppercase(Locale.ROOT) ?: "—"}\n/ ${result.actualAcceleration?.uppercase(Locale.ROOT) ?: uiText.bench("Unconfirmed", "غير مؤكد", "未确认")}", 140.dp)
                            TableCell(elapsed(BenchmarkTableModel.warmMs(result)), 100.dp)
                            TableCell(BenchmarkTableModel.audioSpeed(result)?.let { String.format(Locale.getDefault(), "%.2f×", it) }
                                ?: BenchmarkTableModel.translationMsPerSentence(result)?.let(::elapsed) ?: "—", 120.dp)
                            TableCell(elapsed(result.loadMs.takeIf { result.error == null && !BenchmarkTableModel.disqualified(result) }), 90.dp)
                            TableCell(elapsed(BenchmarkTableModel.firstTextMs(result)), 100.dp)
                            TableCell(BenchmarkTableModel.qualityValue(result)?.let {
                                String.format(Locale.getDefault(), "%.1f%%", it * 100) + when {
                                    result.target.isNotBlank() -> " chrF++"
                                    result.source == "zh" || result.wordErrorRate == null -> " CER"
                                    else -> " WER"
                                }
                            } ?: "—", 120.dp)
                            TableCell(result.modelBytes?.let(::fileSize) ?: "—", 90.dp)
                            TableCell(when {
                                BenchmarkTableModel.disqualified(result) -> uiText.bench("Disqualified", "مستبعد", "已取消资格")
                                result.error != null -> uiText.bench("Failed", "فشل", "失败")
                                BenchmarkTableModel.completed(result) -> uiText.bench("Completed", "مكتمل", "已完成")
                                else -> uiText.bench("Empty / incomplete", "فارغ / غير مكتمل", "空 / 未完成")
                            }, 120.dp, error = result.error != null || BenchmarkTableModel.disqualified(result))
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ page = safePage - 1 }, enabled = safePage > 0) { Text(uiText.bench("Previous", "السابق", "上一页")) }
                    Text("${safePage + 1} / $pages", style = MaterialTheme.typography.labelLarge)
                    TextButton({ page = safePage + 1 }, enabled = safePage + 1 < pages) { Text(uiText.bench("Next", "التالي", "下一页")) }
                }
            }
            Text(uiText.bench(
                "Lower time, WER and CER are better. Higher audio speed and chrF++ are better. Audio speed is accelerated replay; first output may be a final-only result. Disk size is not RAM. Blank metrics were not measured. No human voice-quality score is inferred.",
                "الوقت وWER وCER الأقل أفضل. سرعة الصوت وchrF++ الأعلى أفضل. السرعة من إعادة تشغيل مسرعة، وقد يكون أول مخرج نهائيا فقط. حجم الملفات ليس RAM. القياسات الفارغة غير مقاسة. لا يُفترض تقييم بشري لجودة الصوت.",
                "耗时、WER、CER 越低越好，音频速度和 chrF++ 越高越好。音频速度为加速回放，首个输出可能仅为最终结果。磁盘大小不等于内存；空白指标未测量，不推断人类听感评分。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(uiText(R.string.benchmark_score_explanation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    detail?.let { row ->
        BenchmarkResultDetails(row, onDismiss = { detail = null }, onCompare = {
            chosenCohort = BenchmarkTableModel.cohort(row); allInputs = false; allLanguages = false; detail = null
        }, canCompare = row.task == task && row.source == source && row.target == target)
    }
}

@Composable
private fun TableCell(value: String, width: Dp, heading: Boolean = false, error: Boolean = false) {
    Text(value, Modifier.width(width), style = if (heading) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun BenchmarkResultDetails(result: BenchmarkResult, onDismiss: () -> Unit, onCompare: () -> Unit, canCompare: Boolean) {
    val uiText = rememberUiText()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(result.model) }, text = {
        SelectionContainer {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DateFormat.getDateTimeInstance().format(Date(result.timestamp)), style = MaterialTheme.typography.labelMedium)
                result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (BenchmarkTableModel.disqualified(result)) Text(uiText.bench(
                    "Disqualified. This model/backend is skipped for the rest of its queue. Interrupted work does not receive speed or quality scores.",
                    "مستبعد. يُتخطى هذا النموذج ومسار تنفيذه في بقية الطابور. لا تحصل المحاولة المتوقفة على درجات سرعة أو جودة.",
                    "已取消资格。该模型/后端将跳过队列后续测试。中断的工作不会获得速度或质量分数。"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("${result.task} · ${result.source}${if (result.target.isBlank()) "" else " → ${result.target}"}\n${result.device}")
                Text("${uiText.bench("Recognition language", "لغة التعرف", "识别语言")}: ${recognitionLabel(result)}")
                Text("${uiText.bench("Runtime evidence", "معلومات التنفيذ", "运行时证据")}:\n${result.runtime}")
                Text("${uiText.bench("Requested backend", "مسار التنفيذ المطلوب", "请求后端")}: ${BenchmarkTableModel.requestedBackend(result) ?: "—"}\n" +
                    "${uiText.bench("Actual backend", "مسار التنفيذ الفعلي", "实际后端")}: ${result.actualAcceleration ?: uiText.bench("Not confirmed", "غير مؤكد", "未确认")}")
                result.environment?.let { environment ->
                    HorizontalDivider()
                    Text(uiText.bench("Phone conditions: before → after", "حالة الهاتف: قبل ← بعد", "设备状态：之前 → 之后"), style = MaterialTheme.typography.titleSmall)
                    fun pair(value: (BenchmarkEnvironmentSnapshot) -> String) = "${value(environment.before)} → ${value(environment.after)}"
                    Text("${uiText.bench("Battery", "البطارية", "电量")}: ${pair { it.batteryPercent?.let { value -> "$value%" } ?: "—" }}\n" +
                        "${uiText.bench("Battery temperature", "حرارة البطارية", "电池温度")}: ${pair { it.batteryCelsius?.let { value -> String.format(Locale.getDefault(), "%.1f °C", value) } ?: "—" }}\n" +
                        "${uiText.bench("Thermal status (0–6)", "حالة الحرارة (0–6)", "温控状态（0–6）")}: ${pair { it.thermalStatus?.toString() ?: "—" }}\n" +
                        "${uiText.bench("App PSS memory", "ذاكرة التطبيق PSS", "应用 PSS 内存")}: ${pair { it.processPssBytes?.let(::fileSize) ?: "—" }}\n" +
                        "${uiText.bench("Available system memory", "ذاكرة النظام المتاحة", "系统可用内存")}: ${pair { it.availableSystemMemoryBytes?.let(::fileSize) ?: "—" }}")
                    Text(uiText.bench("Instantaneous samples outside timing, not peak or model RAM. Thermal status: 0 = none, 6 = shutdown. Other apps and charging affect these readings.",
                        "عينات لحظية خارج توقيت الاختبار، وليست ذروة الاستهلاك أو ذاكرة النموذج. حالة الحرارة: 0 = طبيعية و6 = إغلاق. تؤثر التطبيقات الأخرى والشحن في القراءات.",
                        "计时外的瞬时采样，并非峰值或模型内存。温控状态：0 = 无限制，6 = 关机。其他应用和充电会影响读数。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${uiText.bench("Model identity", "هوية النموذج", "模型标识")}: ${result.identity}\n${uiText.bench("Input SHA-256", "SHA-256 للمدخل", "输入 SHA-256")}: ${result.inputHash}\n" +
                    "${uiText.bench("Run", "التشغيل", "运行")}: ${result.runId}\n${uiText.bench("Protocol", "البروتوكول", "协议")}: ${result.protocolVersion}\n${result.scoringNormalization.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall)
                result.computeMs.forEachIndexed { index, compute ->
                    HorizontalDivider()
                    Text("${uiText.bench("Pass", "المرور", "轮次")} ${index + 1}: ${elapsed(compute)} · ${uiText.bench("first output", "أول مخرج", "首个输出")} ${elapsed(result.firstTextMs.getOrNull(index))}",
                        style = MaterialTheme.typography.labelMedium)
                    Text(result.texts.getOrNull(index).orEmpty().ifBlank { uiText.bench("Empty output", "مخرجات فارغة", "空输出") })
                }
                result.referenceText?.let { Text("${uiText.bench("Reference", "المرجع", "参考")}: $it") }
                result.samples.forEach { sample ->
                    HorizontalDivider()
                    Text("${sample.id} · ${elapsed(sample.computeMs)}${if (sample.silence) " · ${uiText.bench("silence check", "فحص الصمت", "静音测试")}" else ""}",
                        style = MaterialTheme.typography.labelMedium)
                    sample.sourceText?.let { Text("${uiText.bench("Input", "المدخل", "输入")}: $it") }
                    sample.reference?.let { Text("${uiText.bench("Reference", "المرجع", "参考")}: $it") }
                    Text(sample.text.ifBlank { uiText.bench("Empty output", "مخرجات فارغة", "空输出") })
                }
            }
        }
    }, confirmButton = {
        if (canCompare) TextButton(onCompare) { Text(uiText.bench("Compare this input", "قارن هذا المدخل", "对比此输入")) }
    }, dismissButton = { TextButton(onDismiss) { Text(uiText.bench("Close", "إغلاق", "关闭")) } })
}

/** Uses only recorded decoder metadata; it never guesses from the benchmark's fixture language. */
@Composable
private fun recognitionLabel(result: BenchmarkResult): String {
    val uiText = rememberUiText()
    val setting = BenchmarkTableModel.recognitionSetting(result)
        ?: return uiText.bench("Unrecorded", "غير مسجل", "未记录")
    val control = when (setting.control) {
        "forced" -> uiText.bench("Language hint", "تلميح اللغة", "语言提示")
        "automatic" -> uiText.bench("Automatic", "تلقائي", "自动")
        else -> uiText.bench("Fixed", "ثابت", "固定")
    }
    return "$control · ${setting.language.uppercase(Locale.ROOT)}"
}

private fun elapsed(ms: Double?): String = if (ms == null || !ms.isFinite() || ms < 0) "—"
    else if (ms < 1000) "${ms.roundToInt()} ms" else String.format(Locale.getDefault(), "%.2f s", ms / 1000.0)

private fun fileSize(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else if (bytes < 1024L * 1024 * 1024) String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024))
    else String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
