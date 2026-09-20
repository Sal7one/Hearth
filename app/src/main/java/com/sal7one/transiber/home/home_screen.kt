package com.sal7one.transiber.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
internal fun HomeScreen(onCaptions: () -> Unit, onTalk: (Boolean) -> Unit,
    onTranslate: () -> Unit, onCamera: () -> Unit, onReading: () -> Unit, entryRevision: Int = 0, onSetup: () -> Unit) {
    val context = LocalContext.current
    val initial = remember(entryRevision) { HomeServiceStore.selected(context) }
    val pager = rememberPagerState(initialPage = initial.ordinal, pageCount = { HomeService.entries.size })
    val scope = rememberCoroutineScope()
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
        }
    }
    Column(Modifier.fillMaxSize()) {
        HomeEasySetupEntry(onSetup = onSetup)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous service")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = "Service ${pager.settledPage + 1} of ${HomeService.entries.size}" }) {
                HomeService.entries.forEachIndexed { index, _ ->
                    Surface(shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (index == pager.settledPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(width = if (index == pager.settledPage) 22.dp else 6.dp, height = 6.dp)) {}
                }
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.settledPage + 1) } },
                enabled = pager.settledPage < HomeService.entries.lastIndex && !pager.isScrollInProgress) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next service")
            }
        }
    }
}
