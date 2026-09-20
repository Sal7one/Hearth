package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceMenu(label: String,selected: String,values: List<Pair<String,String>>,choose: (String)->Unit) {
    val uiText = rememberUiText()

    var expanded by remember {mutableStateOf(false)}
    OutlinedButton(onClick={expanded=true},enabled=values.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {Text("$label: ${values.firstOrNull {it.first==selected}?.second ?: uiText(UiR.string.choose_value)}")}
    if(expanded)AlertDialog(onDismissRequest={expanded=false},title={Text(label)},confirmButton={TextButton(onClick={expanded=false}){Text(uiText(UiR.string.ui_close_bbfa7))}},text={
        val scroll=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=values.indexOfFirst {it.first==selected}.coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=400.dp),state=scroll) {
            items(values.size){index ->val (id,text)=values[index]
                TextButton(onClick={choose(id);expanded=false},modifier=Modifier.fillMaxWidth().semantics {this.selected=id==selected}){Text(if(id==selected)"✓ $text" else text)}
            }
        }
    })
}
