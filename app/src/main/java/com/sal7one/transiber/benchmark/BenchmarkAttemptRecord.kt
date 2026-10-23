package com.sal7one.transiber.benchmark

import android.content.Context
import android.os.Build
import com.sal7one.transiber.BuildConfig

/** Parent and worker use identical experiment identities; no model reads are needed. */
internal object BenchmarkAttemptRecord {
    fun validate(batch: BenchmarkBatch,inputs: List<BenchmarkInput>) {
        require(batch.models.isNotEmpty()) {"Choose installed models first"}
        val task=batch.models.first().task
        require(batch.models.all {it.task==task}) {"Compare speech, translation and voices separately"}
        require(batch.measuredPasses in 3..10) {"Choose 3–10 measured passes"}
        if(inputs.isNotEmpty()) {
            require(inputs.size<=32 && inputs.all {if(task==BenchmarkTask.SPEECH)it.clip!=null else it.text.isNotBlank() && it.text.length<=500 && it.clip==null}) {"Benchmark samples do not match the selected task"}
        } else if(task==BenchmarkTask.SPEECH)require(batch.clip!=null) {"Choose a WAV recording first"}
        else require(batch.text.isNotBlank() && batch.text.length<=500) {"Use 1–500 characters of source text"}
        if(task==BenchmarkTask.TRANSLATION)require(batch.source!=batch.target) {"Choose different source and translation languages"}
        require(batch.referenceText==null || batch.referenceText.length<=8000) {"Reference text must be 8,000 characters or fewer"}
    }

    fun inputHash(inputs: List<BenchmarkInput>, clip: BenchmarkAudio.Clip?, text: String,
                  reference: String?, source: String, target: String, task: BenchmarkTask): String =
        benchmarkHash(if(inputs.isNotEmpty())inputs.joinToString("\u0000") {
            listOf(it.id,it.clip?.sha256 ?: it.text,it.reference.orEmpty(),it.silence.toString()).joinToString("\u0001")
        }.toByteArray() else "$source\u0000$target\u0000${clip?.takeIf {task==BenchmarkTask.SPEECH}?.sha256.orEmpty()}\u0000$text\u0000${reference.orEmpty()}".toByteArray())

    fun device() = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    fun configuration(context: Context,candidate: BenchmarkCandidate,passes: Int,dialect: String,seconds: Int): String? {
        val installed=context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime
        return BenchmarkExecutionMetadata.configurationHash(candidate,passes,
            "jisr-dialect=$dialect;app-install=$installed;os=${Build.FINGERPRINT};isolated-phases-v2;sample-timeout-seconds=${BenchmarkAttemptPolicy.seconds(seconds)};startup=30;load=120;cleanup=10")
    }

    fun failed(context: Context,runId: String,candidate: BenchmarkCandidate,batch: BenchmarkBatch,
               inputs: List<BenchmarkInput>,dialect: String,error: String,reason: String): BenchmarkResult {
        val recognition=runCatching {BenchmarkLanguagePlan.recognition(candidate,batch.source)}.getOrNull()
        val reference=if(inputs.isEmpty())batch.referenceText else inputs.filterNot {it.silence}.mapNotNull {it.reference}
            .takeIf {it.isNotEmpty()}?.joinToString(" ")
        return BenchmarkResult(runId=runId,timestamp=System.currentTimeMillis(),
            model=candidate.label+" · "+candidate.acceleration.orEmpty().uppercase(java.util.Locale.ROOT),
            identity=BenchmarkAccelerationPlan.identity(candidate),route=candidate.route,
            inputHash=inputHash(inputs,batch.clip,batch.text,batch.referenceText,batch.source,batch.target,candidate.task),
            source=batch.source,target=if(candidate.task==BenchmarkTask.TRANSLATION)batch.target else "",
            audioMs=if(candidate.task==BenchmarkTask.SPEECH)inputs.sumOf {it.clip?.durationMs ?: 0L}.takeIf {inputs.isNotEmpty()} ?: (batch.clip?.durationMs ?: 0L) else 0L,
            loadMs=0.0,computeMs=emptyList(),texts=emptyList(),runtime="Isolated benchmark worker; incomplete attempt; no timing or quality score",
            firstTextMs=emptyList(),device=device(),error=error,referenceText=reference,
            // A failed last row must not create a different cohort and hide completed
            // models. This describes the input protocol; no measured score is assigned.
            scoringNormalization=reference?.takeIf {candidate.task!=BenchmarkTask.VOICE}?.let {
                if(candidate.task==BenchmarkTask.TRANSLATION)BenchmarkScoring.CHRF_PARAMETERS else BenchmarkScoring.NORMALIZATION},
            protocolVersion=BenchmarkAttemptPolicy.PROTOCOL_VERSION,task=candidate.task.name.lowercase(java.util.Locale.ROOT),requestedAcceleration=candidate.acceleration,
            modelBytes=candidate.artifactBytes ?: candidate.file?.takeIf {it.isFile}?.length(),
            configurationHash=configuration(context,candidate,batch.measuredPasses,dialect,batch.attemptTimeoutSeconds),
            languageControl=recognition?.control,recognitionLanguage=recognition?.language,terminationReason=reason,
            attemptTimeoutSeconds=BenchmarkAttemptPolicy.seconds(batch.attemptTimeoutSeconds))
    }
}
