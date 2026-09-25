package com.sal7one.transiber.sign

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One complete, usable fingerspelling classifier: an ONNX model plus its
 * sister labels file, installed under `filesDir/sign-models/classifiers/`.
 */
internal data class SignClassifierInfo(
    /** Stable identifier, e.g. `asl_classifier`. */
    val id: String,
    /** Language the model fingerspells. */
    val language: SignLanguage,
    /** Installed ONNX model file. */
    val file: File,
    /** Installed `<model>_labels.json` sister file. */
    val labels: File,
)

/** Result of one classifier-file import. */
internal data class SignClassifierInstall(
    val language: SignLanguage,
    /** The file just installed (model or labels). */
    val installedFile: File,
    /**
     * The sister file still needed before the classifier becomes usable;
     * null once the model + labels pair is complete.
     */
    val missingSisterFile: String?,
) {
    val complete: Boolean get() = missingSisterFile == null
}

/**
 * Storage seam for [SignModels]: where sign models live and which
 * classifier is selected. Production is SharedPreferences-backed; tests
 * supply in-memory fakes so plain JUnit covers install/discovery logic.
 */
internal interface SignModelStore {
    /** Root of the sign-model tree (`filesDir/sign-models`). */
    val rootDir: File

    /** Currently selected classifier id, or null. */
    fun readSelectedClassifierId(): String?

    /** Persists the selected classifier id; null clears the selection. */
    fun writeSelectedClassifierId(id: String?)
}

/**
 * Sign-model tree under `filesDir/sign-models/`:
 * - `hands/hand_detector.onnx` + `hands/hand_landmarks_detector.onnx`
 * - `classifiers/asl_classifier.onnx|arsl_classifier.onnx` and their
 *   `*_labels.json` sister files
 *
 * Imports copy a user-picked file (opened by the caller, e.g. from a SAF
 * Uri) into a temp sibling, verify an optional SHA-256 digest, then
 * atomically move it into place — fail-closed: every failure throws with
 * the real message and never publishes a partial file. Discovery only
 * lists complete, usable pairs. All filesystem work must run on
 * Dispatchers.IO; callers own threading.
 */
internal class SignModels(private val store: SignModelStore) {

    /** The hands directory when both hand models are installed, else null. */
    fun handModelDir(): File? {
        val dir = File(store.rootDir, HANDS_DIR)
        val complete = File(dir, HAND_DETECTOR_FILE).isFile &&
            File(dir, HAND_LANDMARKS_DETECTOR_FILE).isFile
        return if (complete) dir else null
    }

    /** Every complete classifier pair, sorted by id. */
    fun availableClassifiers(): List<SignClassifierInfo> =
        CLASSIFIER_STEMS.entries.mapNotNull { (stem, language) ->
            val dir = File(store.rootDir, CLASSIFIERS_DIR)
            val model = File(dir, "$stem.onnx")
            val labels = File(dir, "$stem$LABELS_SUFFIX")
            if (model.isFile && labels.isFile) {
                SignClassifierInfo(id = stem, language = language, file = model, labels = labels)
            } else {
                null
            }
        }.sortedBy { it.id }

    /** The selected classifier when it is still installed and complete. */
    fun selectedClassifier(): SignClassifierInfo? =
        store.readSelectedClassifierId()
            ?.let { id -> availableClassifiers().firstOrNull { it.id == id } }

    /** Persists the selection; throws when the id is unknown or incomplete. */
    fun selectClassifier(id: String): SignClassifierInfo {
        val classifier = availableClassifiers().firstOrNull { it.id == id }
            ?: throw IllegalStateException(
                "Sign classifier '$id' is unknown or incomplete. Import its .onnx model and *_labels.json file first.",
            )
        store.writeSelectedClassifierId(id)
        return classifier
    }

    /** Clears the persisted classifier selection. */
    fun clearSelectedClassifier() = store.writeSelectedClassifierId(null)

    /**
     * Imports one classifier file: `asl_classifier.onnx` /
     * `arsl_classifier.onnx` or their `*_labels.json` sister file. Labels
     * files are parsed before publication so a broken JSON never installs.
     */
    fun installClassifierFromImport(
        input: InputStream,
        fileName: String,
        expectedSha256: String? = null,
    ): SignClassifierInstall {
        val route = routeClassifierFile(fileName)
            ?: throw IllegalArgumentException(
                "'$fileName' is not a sign classifier file. Expected asl_classifier.onnx, " +
                    "arsl_classifier.onnx or their *_labels.json sister files.",
            )
        val (language, isLabels) = route
        val dir = File(store.rootDir, CLASSIFIERS_DIR)
        val installed = stageImport(input, dir, fileName, expectedSha256) { temp ->
            if (isLabels) OnnxLandmarkClassifier.parseLabels(temp.readText(Charsets.UTF_8))
        }
        val stem = CLASSIFIER_STEMS.entries.first { it.value == language }.key
        val missing = if (isLabels) {
            if (File(dir, "$stem.onnx").isFile) null else "$stem.onnx"
        } else {
            if (File(dir, "$stem$LABELS_SUFFIX").isFile) null else "$stem$LABELS_SUFFIX"
        }
        return SignClassifierInstall(language, installed, missing)
    }

    /**
     * Imports one hand model: `hand_detector.onnx` or
     * `hand_landmarks_detector.onnx`. Returns the installed file.
     */
    fun installHandModelsFromImport(
        input: InputStream,
        fileName: String,
        expectedSha256: String? = null,
    ): File {
        require(fileName == HAND_DETECTOR_FILE || fileName == HAND_LANDMARKS_DETECTOR_FILE) {
            "'$fileName' is not a hand model file. Expected $HAND_DETECTOR_FILE or $HAND_LANDMARKS_DETECTOR_FILE."
        }
        return stageImport(input, File(store.rootDir, HANDS_DIR), fileName, expectedSha256)
    }

    /** Copies [input] to a temp sibling, verifies it, then atomically publishes it. */
    private fun stageImport(
        input: InputStream,
        directory: File,
        fileName: String,
        expectedSha256: String?,
        validate: ((File) -> Unit)? = null,
    ): File {
        require(SAFE_FILE_NAME.matches(fileName)) { "Unsafe sign-model file name: $fileName" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.path}" }
        val temp = File.createTempFile("sign-import-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_IMPORT_BYTES) {
                        "Sign model import exceeds the ${MAX_IMPORT_BYTES / (1024L * 1024L)} MB limit: $fileName"
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            check(total > 0L) { "Imported sign model file is empty: $fileName" }
            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expectedSha256, ignoreCase = true)) {
                    "Integrity check failed for $fileName: expected sha256 $expectedSha256, got $actual"
                }
            }
            validate?.invoke(temp)
            val destination = File(directory, fileName)
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return destination
        } finally {
            temp.delete()
        }
    }

    private fun routeClassifierFile(fileName: String): Pair<SignLanguage, Boolean>? {
        for ((stem, language) in CLASSIFIER_STEMS) {
            if (fileName == "$stem.onnx") return language to false
            if (fileName == "$stem$LABELS_SUFFIX") return language to true
        }
        return null
    }

    companion object {
        const val SIGN_MODELS_DIR = "sign-models"
        const val HANDS_DIR = "hands"
        const val CLASSIFIERS_DIR = "classifiers"
        const val HAND_DETECTOR_FILE = "hand_detector.onnx"
        const val HAND_LANDMARKS_DETECTOR_FILE = "hand_landmarks_detector.onnx"
        const val LABELS_SUFFIX = "_labels.json"

        /** Classifier file stems, one per supported [SignLanguage]. */
        val CLASSIFIER_STEMS: Map<String, SignLanguage> = mapOf(
            "asl_classifier" to SignLanguage.ASL_ENGLISH,
            "arsl_classifier" to SignLanguage.ARABIC,
        )

        /** Plain file names only: no separators, no traversal, no hidden files. */
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        /** Hand landmark models are a few MB; anything larger is not a sign model. */
        const val MAX_IMPORT_BYTES: Long = 256L * 1024L * 1024L
    }
}

private const val SIGN_PREFS_NAME = "sign_models"
private const val KEY_SELECTED_CLASSIFIER = "selected_classifier"

/** SharedPreferences-backed store rooted at `filesDir/sign-models`. */
private class AndroidSignModelStore(context: Context) : SignModelStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SIGN_PREFS_NAME, Context.MODE_PRIVATE)

    override val rootDir: File get() = File(appContext.filesDir, SignModels.SIGN_MODELS_DIR)
    override fun readSelectedClassifierId(): String? = prefs.getString(KEY_SELECTED_CLASSIFIER, null)
    override fun writeSelectedClassifierId(id: String?) {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_SELECTED_CLASSIFIER) else editor.putString(KEY_SELECTED_CLASSIFIER, id)
        editor.apply()
    }
}

/** The sign-model tree bound to this app's private storage and prefs. */
internal fun signModels(context: Context): SignModels = SignModels(AndroidSignModelStore(context))

/** Both hand models installed under `filesDir/sign-models/hands`, or null. */
internal fun handModelDir(context: Context): File? = signModels(context).handModelDir()

/** Every complete fingerspelling classifier installed for this app. */
internal fun availableClassifiers(context: Context): List<SignClassifierInfo> =
    signModels(context).availableClassifiers()

/** The persisted classifier selection when it is still usable. */
internal fun selectedClassifier(context: Context): SignClassifierInfo? =
    signModels(context).selectedClassifier()

/** Persists the classifier selection; throws when unknown or incomplete. */
internal fun selectClassifier(context: Context, id: String): SignClassifierInfo =
    signModels(context).selectClassifier(id)

/** Clears the persisted classifier selection. */
internal fun clearSelectedClassifier(context: Context) {
    signModels(context).clearSelectedClassifier()
}

/**
 * Imports a user-picked classifier file (SAF Uri) into the app-private
 * tree. Runs the caller's coroutine/thread — use Dispatchers.IO.
 */
internal fun installClassifierFromImport(
    context: Context,
    uri: Uri,
    expectedSha256: String? = null,
): SignClassifierInstall {
    val fileName = importFileName(context, uri)
    return withImportStream(context, uri) { input ->
        signModels(context).installClassifierFromImport(input, fileName, expectedSha256)
    }
}

/**
 * Imports a user-picked hand model (SAF Uri) into the app-private tree.
 * Runs the caller's coroutine/thread — use Dispatchers.IO.
 */
internal fun installHandModelsFromImport(
    context: Context,
    uri: Uri,
    expectedSha256: String? = null,
): File {
    val fileName = importFileName(context, uri)
    return withImportStream(context, uri) { input ->
        signModels(context).installHandModelsFromImport(input, fileName, expectedSha256)
    }
}

private fun importFileName(context: Context, uri: Uri): String {
    val document = DocumentFile.fromSingleUri(context, uri)
        ?: throw IllegalStateException("Cannot read the picked file: $uri")
    check(document.isFile) { "The picked sign model is not a file: $uri" }
    return document.name ?: throw IllegalStateException("The picked sign model has no file name: $uri")
}

private fun <T> withImportStream(context: Context, uri: Uri, block: (InputStream) -> T): T =
    context.contentResolver.openInputStream(uri)?.use(block)
        ?: throw IllegalStateException("Cannot open the picked file: $uri")
