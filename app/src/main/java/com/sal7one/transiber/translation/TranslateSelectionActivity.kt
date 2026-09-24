package com.sal7one.transiber.translation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R

/** Android text-menu and Sharesheet entry point; the translation UI lives in MainActivity. */
class TranslateSelectionActivity : Activity() {
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
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
        })
        setResult(RESULT_CANCELED)
        finish()
    }
}
