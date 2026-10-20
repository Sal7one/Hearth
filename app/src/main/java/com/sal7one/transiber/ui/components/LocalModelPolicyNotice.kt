package com.sal7one.transiber.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

@Composable
internal fun LocalModelPolicyNotice() {
    val text = rememberUiText()
    Text(text(R.string.tv_local_models_disabled), style = MaterialTheme.typography.bodyMedium)
}
