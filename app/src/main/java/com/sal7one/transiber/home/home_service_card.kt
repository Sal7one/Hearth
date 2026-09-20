package com.sal7one.transiber.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R

@Composable
internal fun HomeServiceCard(service: HomeService, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val artwork = when (service) {
        HomeService.CAPTIONS -> R.drawable.home_captions
        HomeService.CONVERSATION -> R.drawable.home_conversation
        HomeService.FACE -> R.drawable.home_face
        HomeService.TEXT -> R.drawable.home_text
        HomeService.CAMERA -> R.drawable.home_camera
        HomeService.SCREEN -> R.drawable.home_screen
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        ElevatedCard(shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 9.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Image(painterResource(artwork), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.08f))
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(service.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(service.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                        Text(service.action)
                    }
                }
            }
        }
    }
}
