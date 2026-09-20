package com.sal7one.transiber.i18n

import androidx.annotation.StringRes
import java.util.Locale

/** A deferred UI message for capability metadata. Constructing it needs no Android Context. */
data class UiMessage(@param:StringRes val resource: Int, val fallback: String, val arguments: List<Any?> = emptyList()) {
    // Legacy diagnostics and host callers retain an English representation.
    override fun toString(): String = if (arguments.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *arguments.toTypedArray())
}

internal fun UiText.message(message: UiMessage): String = this(message.resource,
    *message.arguments.map { if (it is UiMessage) this.message(it) else it }.toTypedArray())

internal fun UiText.note(choices: com.sal7one.transiber.caption.CaptionLanguageChoices): String =
    choices.localizedNote?.let { message(it) } ?: choices.note
