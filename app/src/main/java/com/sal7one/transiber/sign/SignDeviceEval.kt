package com.sal7one.transiber.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sal7one.common_jni.sign.SignHands
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device acceptance evaluation for the sign pipeline (QA builds only —
 * gated by the `.qa` package suffix in SignStartActivity; production builds
 * never reach it).
 *
 * Driven by adb: the host stages `filesDir/sign-eval/` with the eval images
 * and a manifest.json [{file, letter, w, h, hands: [21 x [x,y,z]]}] whose
 * reference landmarks come from mediapipe.solutions.hands (the distribution
 * the classifiers were trained on), then launches SignStartActivity with
 * `run_device_eval=true`. The run measures:
 *
 *   - landmark agreement vs the reference (mean/max |Δxy|, |Δz|),
 *   - fingerspelling accuracy at Hearth's 0.4 gate, with coverage,
 *   - per-stage latency on this device's CPU (detect / classify).
 *
 * Results land in filesDir/sign-eval-result.json; a bad setup fails loudly
 * with the actual error written to the same file — never an empty success.
 */
internal object SignDeviceEval {

    private const val EVAL_DIR = "sign-eval"
    private const val MANIFEST = "manifest.json"
    private const val RESULT = "sign-eval-result.json"

    fun run(filesDir: File): JSONObject {
        val report = JSONObject().put("started_at", System.currentTimeMillis())
        try {
            val evalDir = File(filesDir, EVAL_DIR)
            val manifest = File(evalDir, MANIFEST)
            check(manifest.isFile) { "Missing $EVAL_DIR/$MANIFEST" }
            val entries = JSONArray(manifest.readText())
            check(entries.length() >= 20) { "Eval manifest has only ${entries.length()} images" }

            val handsDir = File(File(File(filesDir, "sign-models"), "hands"), "")
                .let { hands ->
                    if (File(hands, "hand_detector.onnx").isFile &&
                        File(hands, "hand_landmarks_detector.onnx").isFile
                    ) hands else null
                } ?: error("Hand models missing under files/sign-models/hands")
            // Pick the classifier matching the manifest's ground truth:
            // ASCII letters -> ASL model, otherwise the Arabic model.
            val classifiersDir = File(File(filesDir, "sign-models"), "classifiers")
            val gtAscii = (0 until entries.length())
                .map { entries.getJSONObject(it).getString("letter") }
                .firstOrNull { it.isNotEmpty() }?.all { it.code < 128 } == true
            val modelName = if (gtAscii) "asl_classifier.onnx" else "arsl_classifier.onnx"
            val classifierFile = File(classifiersDir, modelName)
            val labelsFile = File(classifiersDir, modelName.removeSuffix(".onnx") + "_labels.json")
            check(classifierFile.isFile && labelsFile.isFile) {
                "$modelName or its labels missing under files/sign-models/classifiers"
            }

            SignHands(handsDir, threads = 2).use { hands ->
                OnnxLandmarkClassifier.load(classifierFile, labelsFile, threads = 2).use { classifier ->
                    run {
                        var matchedHands = 0
                        var refHands = 0
                        var accepted = 0
                        var correct = 0
                        var dxySum = 0.0
                        var dxyMax = 0.0
                        var dzSum = 0.0
                        var dzMax = 0.0
                        var detectMs = 0.0
                        var classifyMs = 0.0
                        var frames = 0
                        val perImage = JSONArray()

                        for (i in 0 until entries.length()) {
                            val entry = entries.getJSONObject(i)
                            val imageFile = File(evalDir, entry.getString("file"))
                            if (!imageFile.isFile) continue
                            val letter = entry.getString("letter")
                            val reference = entry.optJSONArray("hands") ?: JSONArray()
                            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                                ?: error("Cannot decode ${entry.getString("file")}")
                            val buffer = rgbaBuffer(bitmap)

                            val t0 = nowMs()
                            val detected = hands.detect(buffer, bitmap.width, bitmap.height)
                            val t1 = nowMs()
                            frames++
                            detectMs += t1 - t0

                            val refList = (0 until reference.length()).map { refIdx ->
                                val hand = reference.getJSONArray(refIdx)
                                Array(21) { p ->
                                    Triple(
                                        hand.getJSONArray(p).optDouble(0).toFloat(),
                                        hand.getJSONArray(p).optDouble(1).toFloat(),
                                        hand.getJSONArray(p).optDouble(2).toFloat(),
                                    )
                                }
                            }
                            refHands += refList.size

                            val observation = SignFrameDecoder.fromDetectedHands(
                                detected, timestampMs = 0L, minHandScore = 0.5f,
                            )
                            val hypothesis = if (observation.hands.isNotEmpty()) {
                                val t2 = nowMs()
                                val h = classifier.classify(listOf(observation))
                                classifyMs += nowMs() - t2
                                h
                            } else null

                            val ours = detected.firstOrNull()
                            if (ours != null && refList.isNotEmpty()) {
                                val flat = ours.landmarks
                                val best = refList.minBy { ref ->
                                    (0 until 21).sumOf { p ->
                                        val dx = (flat[p * 3] - ref[p].first).toDouble()
                                        val dy = (flat[p * 3 + 1] - ref[p].second).toDouble()
                                        dx * dx + dy * dy
                                    }
                                }
                                var sumXy = 0.0
                                var sumZ = 0.0
                                for (p in 0 until 21) {
                                    val dx = (flat[p * 3] - best[p].first).toDouble()
                                    val dy = (flat[p * 3 + 1] - best[p].second).toDouble()
                                    val dz = (flat[p * 3 + 2] - best[p].third).toDouble()
                                    val d = sqrt(dx * dx + dy * dy)
                                    sumXy += d
                                    sumZ += abs(dz)
                                    if (d > dxyMax) dxyMax = d
                                    if (abs(dz) > dzMax) dzMax = abs(dz)
                                }
                                matchedHands++
                                dxySum += sumXy / 21
                                dzSum += sumZ / 21
                            }

                            if (hypothesis != null && hypothesis.confidence >= 0.4f) {
                                accepted++
                                if (hypothesis.label == letter) correct++
                            }
                            perImage.put(
                                JSONObject()
                                    .put("file", entry.getString("file"))
                                    .put("letter", letter)
                                    .put("detected", detected.size)
                                    .put("label", hypothesis?.label ?: JSONObject.NULL)
                                    .put("confidence", hypothesis?.confidence ?: 0.0),
                            )
                            bitmap.recycle()
                        }

                        check(frames > 0) { "No eval images ran" }
                        val xyDenom = matchedHands.coerceAtLeast(1)
                        report.put("ok", true)
                            .put("classifier", modelName)
                            .put("frames", frames)
                            .put("ref_hands", refHands)
                            .put("matched_hands", matchedHands)
                            .put("dxy_mean", round5(dxySum / xyDenom))
                            .put("dxy_max", round5(dxyMax))
                            .put("dz_mean", round5(dzSum / xyDenom))
                            .put("dz_max", round5(dzMax))
                            .put("accepted", accepted)
                            .put("correct", correct)
                            .put("accuracy_at_0.4", if (accepted > 0) round5(correct.toDouble() / accepted) else 0.0)
                            .put("coverage_at_0.4", round5(accepted.toDouble() / frames))
                            .put("detect_ms_mean", round5(detectMs / frames))
                            .put("classify_ms_mean", round5(classifyMs / frames.coerceAtLeast(1)))
                            .put("per_image", perImage)
                    }
                }
            }
        } catch (e: Exception) {
            report.put("ok", false).put("error", e.message ?: e.toString())
        }
        File(filesDir, RESULT).writeText(report.toString(2))
        return report
    }

    private fun rgbaBuffer(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())  // R
            buffer.put(((pixel shr 8) and 0xFF).toByte())   // G
            buffer.put((pixel and 0xFF).toByte())           // B
            buffer.put(255.toByte())                        // A
        }
        buffer.rewind()
        return buffer
    }

    private fun nowMs(): Double = System.nanoTime() / 1_000_000.0

    private fun round5(v: Double): Double = kotlin.math.round(v * 100000.0) / 100000.0
}
