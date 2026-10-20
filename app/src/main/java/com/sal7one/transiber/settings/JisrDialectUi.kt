package com.sal7one.transiber.settings

import androidx.compose.runtime.Composable
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.ModelFilterPicker

@Composable
internal fun JisrDialectPicker(value: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val text = rememberUiText()
    val labels = linkedMapOf("ar" to R.string.jisr_msa, "arz" to R.string.jisr_egyptian,
        "ary" to R.string.jisr_moroccan, "arq" to R.string.jisr_algerian,
        "aeb" to R.string.jisr_tunisian, "ayl" to R.string.jisr_libyan,
        "apc" to R.string.jisr_lebanese, "ajp" to R.string.jisr_syrian,
        "ajp-ps" to R.string.jisr_palestinian, "ars" to R.string.jisr_saudi,
        "afb" to R.string.jisr_gulf, "apd" to R.string.jisr_sudanese,
        "acq" to R.string.jisr_yemeni, "acm" to R.string.jisr_iraqi)
    ModelFilterPicker(text(R.string.jisr_dialect), value, labels.map { it.key to text(it.value) }, onSelect, enabled = enabled)
}
