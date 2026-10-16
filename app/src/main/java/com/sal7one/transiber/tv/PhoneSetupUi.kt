package com.sal7one.transiber.tv

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.setup.EasySetupActions
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

internal fun phoneSetupAddress(context: Context): InetAddress {
    check(ByokPolicy.FEATURE_BYOK) { "Phone network setup is unavailable in the offline build." }
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
    return networks.distinct().firstNotNullOfOrNull { network ->
        val caps = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@firstNotNullOfOrNull null
        manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    } ?: error(context.uiText()(R.string.tv_pair_no_network))
}

private fun qrBitmap(value: String): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 3))
    val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun PhoneSetupDialog(config: CaptionOverlayConfig, onDismiss: () -> Unit) {
    if (!ByokPolicy.FEATURE_BYOK) return
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf<PhoneSetupServer?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyboardHelp by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onDismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        var owned: PhoneSetupServer? = null
        try {
            withContext(Dispatchers.IO) {
                val voice = CloudVoiceStore.forProvider(context, CloudVoiceProvider.OPENAI).voice.takeIf { it in setOf("coral", "alloy", "nova", "onyx") } ?: "coral"
                val targets = CaptionLanguages.openAiTranslation.codes
                val defaults = JSONObject().put("source", config.source.name).put("mode", config.mode.name)
                    .put("target", config.target.languageTag.takeIf { it in targets } ?: "en").put("voice", voice)
                    .put("readAloud", config.speakCaptions).put("volume", config.speakerVolume)
                    .put("languages", JSONArray().apply { targets.sorted().forEach { code ->
                        put(JSONObject().put("code", code).put("label", text.languageLabel(LanguageCatalog.option(code))))
                    } })
                owned = PhoneSetupServer(phoneSetupAddress(context), { name -> context.assets.open("phone-setup/$name").use { it.readBytes() } }, defaults, targets)
                qr = qrBitmap(owned!!.qrUrl)
            }
            server = owned
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        finally { owned?.close() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text(R.string.tv_pair_title), style = MaterialTheme.typography.headlineLarge)
                Text(text(R.string.tv_pair_intro), style = MaterialTheme.typography.titleMedium)
                val live = server
                if (live == null) {
                    if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    RemoteButton(text(R.string.ui_back_b52b3), onClick = onDismiss)
                } else {
                    val state by live.state.collectAsState()
                    val actionFocus = remember { FocusRequester() }
                    LaunchedEffect(state.phase) { actionFocus.requestFocus() }
                    when (state.phase) {
                        PairingPhase.WAITING -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                qr?.let { Image(it.asImageBitmap(), text(R.string.tv_pair_qr_description), Modifier.size(260.dp).background(Color.White)) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(text(R.string.tv_pair_code, live.qrUrl.substringAfter("token=").take(6).uppercase()), style = MaterialTheme.typography.headlineSmall)
                                    Text(text(R.string.tv_pair_steps), style = MaterialTheme.typography.bodyLarge)
                                    Text(text(R.string.tv_pair_trusted_network), style = MaterialTheme.typography.bodyMedium)
                                    Text(text(R.string.tv_pair_expiry), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            RemoteButton(text(R.string.tv_pair_cancel), focus = actionFocus, onClick = onDismiss)
                        }
                        PairingPhase.REVIEW, PairingPhase.SAVING -> {
                            val review = checkNotNull(state.review)
                            Text(text(if (review.replacesKey) R.string.tv_pair_key_received else R.string.tv_pair_key_reuse), style = MaterialTheme.typography.titleLarge)
                            Text(text.label(CaptionSource.valueOf(review.source)) + " · " + text.label(CaptionMode.valueOf(review.mode)), style = MaterialTheme.typography.titleLarge)
                            Text(text(R.string.ui_translate_to_1_s_f0b21, text.languageLabel(LanguageCatalog.option(review.target))))
                            Text(text(if (review.readAloud) R.string.tv_voice_on else R.string.tv_voice_off) + " · ${review.voice} · ${review.volume}%")
                            if (state.phase == PairingPhase.SAVING) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                RemoteButton(text(R.string.ui_saving_56a22), focus = actionFocus, onClick = {})
                            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                RemoteButton(text(R.string.tv_pair_approve), focus = actionFocus, modifier = Modifier.heightIn(min = 64.dp), onClick = {
                                    scope.launch {
                                        try { live.approve { proposal ->
                                            val actions = EasySetupActions(context)
                                            actions.applyTvOpenAi(proposal.key())
                                            actions.applyTvVoice(proposal.review.voice, proposal.review.readAloud, proposal.review.volume)
                                            CaptionConfigStore.update(context) { it.copy(source = CaptionSource.valueOf(proposal.review.source),
                                                mode = CaptionMode.valueOf(proposal.review.mode), target = TranslationTarget.of(proposal.review.target)) }
                                        } } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { /* Actual saving error is published by the server's state. */ }
                                    }
                                })
                                RemoteButton(text(R.string.tv_pair_reject), onClick = live::reject)
                            }
                        }
                        PairingPhase.COMPLETE -> {
                            Text(text(R.string.tv_pair_saved), style = MaterialTheme.typography.headlineSmall)
                            RemoteButton(text(R.string.ui_back_b52b3), focus = actionFocus, onClick = onDismiss)
                        }
                        PairingPhase.EXPIRED, PairingPhase.CLOSED -> {
                            Text(text(R.string.tv_pair_expired))
                            RemoteButton(text(R.string.ui_back_b52b3), focus = actionFocus, onClick = onDismiss)
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                RemoteButton(text(R.string.tv_phone_keyboard), onClick = { keyboardHelp = true })
            }
        }
    }
    if (keyboardHelp) PhoneKeyboardHelpDialog { keyboardHelp = false }
}

/** Uses the system's authenticated remote keyboard; Hearth never handles its pairing secrets. */
@Composable
internal fun PhoneKeyboardHelpDialog(onDismiss: () -> Unit) {
    val text = rememberUiText()
    val uri = "https://support.google.com/googletv/answer/11136134?hl=en"
    val qr = remember { qrBitmap(uri) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text(R.string.tv_phone_keyboard), style = MaterialTheme.typography.headlineLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Image(qr.asImageBitmap(), text(R.string.tv_keyboard_qr_description), Modifier.size(240.dp).background(Color.White))
                    Text(text(R.string.tv_phone_keyboard_steps), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                RemoteButton(text(R.string.ui_back_b52b3), onClick = onDismiss)
            }
        }
    }
}
