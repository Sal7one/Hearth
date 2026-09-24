package com.sal7one.transiber.translation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import com.sal7one.transiber.ui.theme.HearthTheme

/** A floating text-selection result in the caller's task, not a MainActivity trampoline. */
class TranslateSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = try {
            when (intent.action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
                        Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                    }
                else -> null
            }
        } catch (error: RuntimeException) {
            Toast.makeText(this, error.message ?: error.toString(), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (selected.isNullOrBlank()) {
            Toast.makeText(this, R.string.action_hearth_translate_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setResult(RESULT_CANCELED)
        setFinishOnTouchOutside(true)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val canReplace = intent.action == Intent.ACTION_PROCESS_TEXT &&
            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        setContent {
            HearthTheme {
                QuickTranslateContent(
                    selectedText = selected,
                    canReplace = canReplace,
                    onDismiss = ::finish,
                    onCopy = { translation ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.action_hearth_translate), translation),
                        )
                        finish()
                    },
                    onReplace = { translation ->
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, translation))
                        finish()
                    },
                    onOpenFull = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            action = Intent.ACTION_PROCESS_TEXT
                            type = "text/plain"
                            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                        })
                        finish()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }
}
