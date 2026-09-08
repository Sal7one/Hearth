package com.sal7one.transiber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.CaptionScreen
import com.sal7one.transiber.ui.theme.FFmpegStudioTheme
import com.sal7one.transiber.models.ModelsScreen
import com.sal7one.transiber.downloads.DownloadsScreen

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val nativeFailure = try { com.sal7one.common_jni.CommonJni.init(applicationContext); null }
   catch(e: Exception) { e.message ?: e.toString() }
  enableEdgeToEdge()
  setContent {
   FFmpegStudioTheme {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
     NavigationBar {
      listOf("Captions", "Models", "Downloads").forEachIndexed { index, label ->
       NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Text(listOf("CC", "AI", "↓")[index]) }, label = { Text(label) })
      }
     }
    }) { padding ->
     Column(Modifier.fillMaxSize().padding(padding)) {
      Text("Real time transiber", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
      nativeFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      Box(Modifier.weight(1f)) {
       when(page) { 0 -> CaptionScreen(onBrowseModels = { page = 1 }); 1 -> ModelsScreen(); else -> DownloadsScreen() }
      }
     }
    }
   }
  }
 }
}
