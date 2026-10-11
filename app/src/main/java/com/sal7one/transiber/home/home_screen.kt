package com.sal7one.transiber.home

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.sal7one.transiber.i18n.title
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(onCaptions: () -> Unit, onTalk: (Boolean) -> Unit,
    onTranslate: () -> Unit, onCamera: () -> Unit, onReading: () -> Unit,
    onTts: () -> Unit, onNotes: () -> Unit, onDocuments: () -> Unit, entryRevision: Int = 0, onSetup: () -> Unit) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    val initial = remember(entryRevision) { HomeServiceStore.selected(context) }
    val pager = rememberPagerState(initialPage = initial.ordinal, pageCount = { HomeService.entries.size })
    val scope = rememberCoroutineScope()
    var showFeatures by remember { mutableStateOf(false) }
    LaunchedEffect(entryRevision) {
        // An external link or Classic tab may have used another feature since Home was shown.
        pager.scrollToPage(initial.ordinal)
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            HomeServiceStore.remember(context, HomeService.entries[index])
        }
    }
    fun open(service: HomeService) {
        HomeServiceStore.remember(context, service)
        when (service) {
            HomeService.CAPTIONS -> onCaptions()
            HomeService.CONVERSATION -> onTalk(false)
            HomeService.FACE -> onTalk(true)
            HomeService.TEXT -> onTranslate()
            HomeService.CAMERA -> onCamera()
            HomeService.SCREEN -> onReading()
            HomeService.TTS -> onTts()
            HomeService.NOTES -> onNotes()
            HomeService.DOCUMENTS -> onDocuments()
        }
    }
    if (showFeatures) ModalBottomSheet(onDismissRequest = { showFeatures = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(uiText(UiR.string.home_all_features), style = MaterialTheme.typography.headlineSmall)
            HomeService.entries.chunked(2).forEach { services ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    services.forEach { service ->
                        FilledTonalButton(onClick = { showFeatures = false; open(service) }, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) {
                            Text(uiText.title(service))
                        }
                    }
                    if (services.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HomeEasySetupEntry(onSetup = onSetup)
        TextButton(onClick = { showFeatures = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(uiText(UiR.string.home_all_features))
        }
        HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 30.dp), pageSpacing = 14.dp,
            key = { HomeService.entries[it].id }, verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { index ->
            val distance = abs((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
            HomeServiceCard(HomeService.entries[index], Modifier.graphicsLayer {
                scaleX = 1f - distance * .045f
                scaleY = 1f - distance * .045f
                translationY = distance * 10.dp.toPx()
            }) { open(HomeService.entries[index]) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.settledPage - 1) } },
                enabled = pager.settledPage > 0 && !pager.isScrollInProgress) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(UiR.string.ui_previous_service_9b734))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_service_1_s_of_2_s_04283, pager.settledPage + 1, HomeService.entries.size) }) {
                HomeService.entries.forEachIndexed { index, _ ->
                    Surface(shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (index == pager.settledPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(width = if (index == pager.settledPage) 22.dp else 6.dp, height = 6.dp)) {}
                }
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.settledPage + 1) } },
                enabled = pager.settledPage < HomeService.entries.lastIndex && !pager.isScrollInProgress) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, uiText(UiR.string.ui_next_service_6d174))
            }
        }
    }
}
