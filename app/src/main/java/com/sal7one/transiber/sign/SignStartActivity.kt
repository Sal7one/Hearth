package com.sal7one.transiber.sign

import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.runtime.LocalWorkGate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Transparent permission/setup trampoline for sign fingerspelling
 * (CaptionStartActivity pattern, minus the consent UI):
 *
 * - launched WITHOUT [EXTRA_START] it just opens the in-app sign page;
 * - launched WITH [EXTRA_START] it verifies overlay permission, camera
 *   permission, hand-model availability and a free inference gate, then
 *   starts [SignOverlayService] directly;
 * - anything missing routes to the sign page, whose diagnostics explain
 *   the exact gap instead of starting a session that cannot work.
 */
class SignStartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        if (!intent.getBooleanExtra(EXTRA_START, false)) {
            openSignPage()
            return
        }
        when {
            !Settings.canDrawOverlays(this) -> openSignPage()
            !handsInstalled() -> openSignPage()
            LocalWorkGate.owner.value != null && !SignOverlayService.running.value -> openSignPage()
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                requestNotificationsThenStart()
            else -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            // Notification denial never blocks capture; the bubble keeps its own controls.
            REQUEST_NOTIFICATIONS -> startOverlay()
            REQUEST_CAMERA -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                requestNotificationsThenStart()
            } else openSignPage()
        }
    }

    /** Notification denial never blocks camera capture; the bubble keeps its own controls. */
    private fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startOverlay()
    }

    private fun startOverlay() {
        SignOverlayService.start(this)
        finish()
    }

    private fun openSignPage() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("page", SIGN_PAGE),
        )
        finish()
    }

    private fun handsInstalled(): Boolean = handModelDir(this) != null

    companion object {
        /** In-app page id for the sign-language screen (see AppNavigation). */
        const val SIGN_PAGE = 16
        const val EXTRA_START = "start"
        private const val REQUEST_CAMERA = 4201
        private const val REQUEST_NOTIFICATIONS = 4202
        fun start(context: Context) {
            context.startActivity(
                Intent(context, SignStartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_START, true),
            )
        }
    }
}
