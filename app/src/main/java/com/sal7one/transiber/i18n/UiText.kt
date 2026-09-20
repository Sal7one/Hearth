package com.sal7one.transiber.i18n

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** A resource snapshot, safe to capture in UI callbacks; never translates provider output. */
class UiText(private val resources: Resources) {
    val locale: java.util.Locale get() = resources.configuration.locales[0]
    operator fun invoke(@StringRes id: Int, vararg arguments: Any?): String =
        if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
}

object AppLocale {
    internal val revision = MutableStateFlow(0)
    fun selected(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    fun select(tag: String) {
        require(tag in setOf("", "en", "ar", "zh"))
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        revision.value++
    }
}

/** Also works for service-owned windows on Android 9–12. No Activity is retained. */
fun Context.uiText(): UiText = UiText(ContextCompat.getContextForLanguage(this).resources)

@Composable
fun rememberUiText(): UiText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val revision by AppLocale.revision.collectAsState()
    return remember(context, configuration, revision) { context.uiText() }
}

/** Service Compose windows do not have an AppCompatActivity to supply RTL configuration. */
@Composable
fun UiLocaleProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val revision by AppLocale.revision.collectAsState()
    val localized = remember(context, configuration, revision) { ContextCompat.getContextForLanguage(context) }
    val direction = if (localized.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
        androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    CompositionLocalProvider(LocalContext provides localized,
        androidx.compose.ui.platform.LocalLayoutDirection provides direction, content = content)
}
