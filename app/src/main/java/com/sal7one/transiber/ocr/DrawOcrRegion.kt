package com.sal7one.transiber.ocr

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A reusable still-image selection surface; it owns no OCR or translation runtime. */
@Composable
internal fun DrawOcrRegion(bitmap: Bitmap, onDismiss: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    val uiText = rememberUiText()

    var size by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember(bitmap) { mutableStateOf<List<Float>?>(null) }
    val crop = bounds?.let { ReadingSelection.crop(bitmap.width,bitmap.height,size.width.toFloat(),size.height.toFloat(),it[0],it[1],it[2],it[3]) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(uiText(UiR.string.ui_draw_around_text_6f9d8),style=MaterialTheme.typography.headlineSmall)
                Text(uiText(UiR.string.ui_circle_or_drag_around_one_bubble_or_paragraph_the_highlighted_rec_ec069))
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged {size=it}) {
                    Image(bitmap.asImageBitmap(),uiText(UiR.string.ui_page_to_select_5d85d),Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    Canvas(Modifier.fillMaxSize().semantics {contentDescription=uiText(UiR.string.ui_draw_a_text_region_whole_image_and_center_region_are_also_availab_edac3)}.pointerInput(bitmap) {
                        detectDragGestures(onDragStart={p->bounds=listOf(p.x,p.y,p.x,p.y)},onDrag={change,_->
                            change.consume();val p=change.position;val b=bounds ?: listOf(p.x,p.y,p.x,p.y)
                            bounds=listOf(minOf(b[0],p.x),minOf(b[1],p.y),maxOf(b[2],p.x),maxOf(b[3],p.y))
                        })
                    }) {
                        crop?.let {c->
                            val scale=minOf(this.size.width/bitmap.width,this.size.height/bitmap.height)
                            val dx=(this.size.width-bitmap.width*scale)/2;val dy=(this.size.height-bitmap.height*scale)/2
                            drawRect(Color(0xFF00BFA5).copy(alpha=.15f),Offset(dx+c.left*scale,dy+c.top*scale),Size(c.width*scale,c.height*scale))
                            drawRect(Color(0xFF00BFA5),Offset(dx+c.left*scale,dy+c.top*scale),Size(c.width*scale,c.height*scale),style=Stroke(3.dp.toPx()))
                        }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={bounds=listOf(0f,0f,size.width.toFloat(),size.height.toFloat())},Modifier.weight(1f)){Text(uiText(UiR.string.ui_whole_image_6bd00))}
                    OutlinedButton(onClick={bounds=listOf(size.width*.25f,size.height*.25f,size.width*.75f,size.height*.75f)},Modifier.weight(1f)){Text(uiText(UiR.string.ui_center_region_7c60f))}
                }
                Text(crop?.let {uiText(UiR.string.ui_selected_1_s_2_s_pixels_f2105, it.width, it.height)} ?: uiText(UiR.string.ui_draw_an_area_or_choose_a_selection_above_deecf))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=onDismiss){Text(uiText(UiR.string.ui_cancel_77dfd))}
                    TextButton(onClick={bounds=null}){Text(uiText(UiR.string.ui_reset_44c57))}
                    Button(onClick={crop?.let {onConfirm(Bitmap.createBitmap(bitmap,it.left,it.top,it.width,it.height))}},enabled=crop!=null){Text(uiText(UiR.string.ui_read_area_9c448))}
                }
            }
        }
    }
}
