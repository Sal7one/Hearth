package com.sal7one.transiber.models

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Sign-language fingerspelling catalog: pinned release artifacts plus tiny label files
 * embedded here because they are generated data, not models. No extension-based guessing;
 * every download is a pinned HTTPS release asset with exact bytes and sha256.
 *
 * Install contract shared with the sign recognition runtime (SignModels.kt):
 *   filesDir/sign-models/hands/hand_detector.onnx
 *   filesDir/sign-models/hands/hand_landmarks_detector.onnx
 *   filesDir/sign-models/classifiers/asl_classifier.onnx
 *   filesDir/sign-models/classifiers/asl_classifier_labels.json   (written at install time)
 *   filesDir/sign-models/classifiers/arsl_classifier.onnx
 *   filesDir/sign-models/classifiers/arsl_classifier_labels.json  (written at install time)
 * Label JSONs are byte-identical to the research training outputs: a compact JSON array,
 * ", " separators, raw UTF-8, no trailing newline. The runtime must find
 * classifiers/<name>.onnx next to <name>_labels.json; [SignModelFiles.import] writes both.
 *
 * FOSS note: this catalog is inert data. Transport and installation stay gated behind
 * ByokPolicy.FEATURE_BYOK in the downloads package, exactly like the speech/OCR/voice
 * entries; the foss flavor has no downloader and never contacts these URLs.
 */

/** One pinned release artifact with its install subdirectory under the sign-models root. */
internal data class SignArtifact(
    val id: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val dir: String,
) {
    val url: String get() = "${SignCatalog.releaseBase}/$fileName"
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid sign artifact ID" }
        require(dir == SignCatalog.HANDS_DIR || dir == SignCatalog.CLASSIFIERS_DIR) { "Unknown sign install directory: $dir" }
        if (sha256 == SignCatalog.SHA256_PENDING_FILL_BEFORE_RELEASE) {
            require(bytes == SignCatalog.PENDING_FILL_BEFORE_RELEASE_BYTES) { "Pending sign artifacts use the placeholder size" }
        } else {
            require(bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}"))) { "Sign downloads require pinned HTTPS artifacts" }
        }
    }
}

/** A fingerspelling classifier plus its embedded label list, keyed by Hearth language code. */
internal data class SignLanguage(
    val language: String,
    val label: String,
    val description: String,
    val classifier: SignArtifact,
    val labels: List<String>,
) {
    val labelsFileName: String get() = classifier.fileName.removeSuffix(".onnx") + "_labels.json"
    init {
        require(language.matches(Regex("[a-z]{2}"))) { "Sign languages use ISO codes" }
        require(labels.isNotEmpty() && labels.distinct().size == labels.size) { "Classifier labels must be non-empty and distinct" }
        require(classifier.dir == SignCatalog.CLASSIFIERS_DIR) { "Classifiers install into the classifiers directory" }
    }
    /** Exact training-output format: compact JSON array, ", " separators, no trailing newline. */
    fun labelsJson(): String = labels.joinToString(", ") { "\"$it\"" }.let { "[$it]" }
}

internal object SignCatalog {
    /**
     * Owner uploads the artifacts built by scripts/sign + research/sign reports to this tag
     * before release. Nothing under this tag is third-party; hashes below pin the exact files.
     */
    const val RELEASE_TAG = "SIGN_MODELS_V1"
    const val releaseBase = "https://github.com/Sal7one/Hearth/releases/download/$RELEASE_TAG"

    /** Placeholder while research/sign artifacts are still building. Entries with this hash must never ship. */
    const val SHA256_PENDING_FILL_BEFORE_RELEASE = "PENDING_FILL_BEFORE_RELEASE"
    const val PENDING_FILL_BEFORE_RELEASE_BYTES = 0L

    /** Install roots under filesDir; the sign runtime resolves these exact paths. */
    const val ROOT_DIR = "sign-models"
    const val HANDS_DIR = "hands"
    const val CLASSIFIERS_DIR = "classifiers"

    // Pinned from the research/sign outputs (2026-09-25): handmodels/ and {asl,arsl}/artifacts/,
    // built by scripts/sign. These pin the exact files the owner uploads to RELEASE_TAG.
    private const val HAND_DETECTOR_BYTES = 4_589_016L
    private const val HAND_DETECTOR_SHA256 = "0923eb04fef6c9cc4c8a3094c990d215341aebb2140eaf022c8cac0370681415"
    private const val HAND_LANDMARKS_BYTES = 10_902_245L
    private const val HAND_LANDMARKS_SHA256 = "4e81514a9141f52e1fb3b387a242c7dc33cb2f2d26c996a28c3d42bf8d012040"
    private const val ASL_CLASSIFIER_BYTES = 76_357L
    private const val ASL_CLASSIFIER_SHA256 = "a63ef94dea583a3c50cc5645565a866206702e96f69b7073ec3d5e0f193a0622"
    private const val ARSL_CLASSIFIER_BYTES = 80_641L
    private const val ARSL_CLASSIFIER_SHA256 = "bc7c34b4cb2431acfe8f441a5c61f982d066e77b268e2882eb038cc274cd029b"

    /** ASL static alphabet: 24 letters, A..Y without the motion letters J and Z. */
    val ASL_LABELS: List<String> = ('A'..'Y').filter { it != 'J' }.map { it.toString() }

    /** Arabic fingerspelling: 28 letters, byte-identical to the training-side arsl_classifier_labels.json. */
    val ARSL_LABELS: List<String> = listOf("ا","ب","ت","ث","ج","ح","خ","د","ذ","ر","ز","س","ش","ص","ض","ط","ظ","ع","غ","ف","ق","ك","ل","م","ن","ه","و","ي")

    /** Shared MediaPipe hand pipeline, used by every language (like the shared OCR detector). */
    val handDetector = SignArtifact("sign-hand-detector", "hand_detector.onnx", HAND_DETECTOR_BYTES, HAND_DETECTOR_SHA256, HANDS_DIR)
    val handLandmarks = SignArtifact("sign-hand-landmarks", "hand_landmarks_detector.onnx", HAND_LANDMARKS_BYTES, HAND_LANDMARKS_SHA256, HANDS_DIR)

    val asl = SignLanguage("en", "ASL alphabet",
        "ASL alphabet (24 static letters; J and Z are motion letters, not supported yet)",
        SignArtifact("sign-asl-classifier", "asl_classifier.onnx", ASL_CLASSIFIER_BYTES, ASL_CLASSIFIER_SHA256, CLASSIFIERS_DIR), ASL_LABELS)

    val arsl = SignLanguage("ar", "Arabic fingerspelling",
        "Arabic fingerspelling (28 letters), trained on AASL and ArSL2018 datasets collected in Saudi Arabia",
        SignArtifact("sign-arsl-classifier", "arsl_classifier.onnx", ARSL_CLASSIFIER_BYTES, ARSL_CLASSIFIER_SHA256, CLASSIFIERS_DIR), ARSL_LABELS)

    val hands = listOf(handDetector, handLandmarks)
    val languages = listOf(asl, arsl)
    val files: List<SignArtifact> = hands + languages.map { it.classifier }

    /** True only when every pinned hash and size is final; pending entries are never offered as downloads. */
    val ready: Boolean get() = files.all { it.bytes > 0 }

    fun find(id: String): SignArtifact? = files.firstOrNull { it.id == id }
    fun forLanguage(language: String): SignLanguage? = languages.firstOrNull { it.language == language }

    /** Honest capability lines for the models screen; a pending release is stated, never hidden. */
    fun describe(): List<String> = languages.map { "${it.label}: ${it.description}" } +
        (if (ready) emptyList() else listOf("$RELEASE_TAG artifacts are not pinned yet (research builds pending)"))
    fun describe(language: String): String? = forLanguage(language)?.description
}

/**
 * Verified installation sink for sign models, mirroring OcrModels/VoiceModels: content
 * identity from the catalog, bounded staging, atomic publication, labels written on install.
 */
internal class SignModelFiles(private val root: File) {
    fun file(artifact: SignArtifact) = File(File(root, artifact.dir), artifact.fileName)
    fun labelsFile(language: SignLanguage) = File(File(root, SignCatalog.CLASSIFIERS_DIR), language.labelsFileName)
    fun installed(artifact: SignArtifact) = file(artifact).let { it.isFile && it.length() == artifact.bytes }
    fun labelsWritten(language: SignLanguage) = labelsFile(language).let {
        it.isFile && it.readBytes().contentEquals(language.labelsJson().toByteArray(Charsets.UTF_8))
    }
    fun ready(language: SignLanguage) = SignCatalog.hands.all(::installed) && installed(language.classifier) && labelsWritten(language)

    fun import(input: InputStream, expected: SignArtifact? = null, cancelled: () -> Unit = {}): SignArtifact {
        root.mkdirs(); val temporary = File.createTempFile("sign-", ".part", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            temporary.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    cancelled(); val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= (expected?.bytes ?: 64L * 1024 * 1024)) { "Sign model file exceeds the supported size" }
                    digest.update(buffer, 0, count); out.write(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val artifact = SignCatalog.files.firstOrNull { it.bytes == size && it.sha256 == hash }
                ?: error("Sign file does not match the pinned sign-model catalog")
            require(expected == null || expected == artifact) { "Downloaded sign model does not match the selected artifact" }
            cancelled()
            File(root, artifact.dir).mkdirs()
            Files.move(temporary.toPath(), file(artifact).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            SignCatalog.languages.firstOrNull { it.classifier == artifact }?.let(::writeLabels)
            return artifact
        } finally { temporary.delete() }
    }

    /** Labels are code, not models: written next to the classifier at install time, byte-identical to training output. */
    private fun writeLabels(language: SignLanguage) {
        val target = labelsFile(language)
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("sign-labels-", ".part", target.parentFile)
        try {
            temporary.writeBytes(language.labelsJson().toByteArray(Charsets.UTF_8))
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
