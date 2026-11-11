package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class PluginFireReceiver : BroadcastReceiver() {

    companion object {
        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")
        if (bundle == null) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        val mode = bundle.getInt("mode", 0)
        val x = bundle.getInt("x", 500)
        val y = bundle.getInt("y", 500)
        val hex = bundle.getString("hex", "#FF0000") ?: "#FF0000"
        val tolerance = bundle.getInt("tolerance", 20)
        val timeout = bundle.getLong("timeout", 2000L)
        val autoTap = bundle.getBoolean("autoTap", false)

        // Jika Mesin Baca Warna belum diaktifkan, langsung lempar ke jalur NO!
        if (!ScreenCaptureService.isReady()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ Mesin Warna Belum Aktif! Buka aplikasi FastAuto dulu.", Toast.LENGTH_SHORT).show()
            }
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // Mode 0 (Cek Instan) dieksekusi langsung tanpa Thread agar Automate 100% akurat menerima YES/NO
        if (mode == 0) {
            val actualHex = ScreenCaptureService.getPixelHex(x, y) ?: "#000000"
            val matched = ScreenCaptureService.compareHex(actualHex, hex, tolerance)

            if (matched && autoTap) {
                FastAccessibilityService.tap(x, y)
            }

            Handler(Looper.getMainLooper()).post {
                val status = if (matched) "✅ COCOK (YES)" else "❌ BEDA (NO)"
                Toast.makeText(context, "Layar: $actualHex | Target: $hex -> $status", Toast.LENGTH_SHORT).show()
            }

            resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            return
        }

        // Mode 1 (Tunggu Warna) & Mode 2 (Scan Area)
        val pendingResult = goAsync()
        Thread {
            var matched = false
            var hitX = x
            var hitY = y
            var lastSeenHex = "#000000"

            if (mode == 1) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeout) {
                    lastSeenHex = ScreenCaptureService.getPixelHex(x, y) ?: "#000000"
                    if (ScreenCaptureService.compareHex(lastSeenHex, hex, tolerance)) {
                        matched = true
                        break
                    }
                    Thread.sleep(5)
                }
            } else if (mode == 2) {
                val radius = 100
                val step = 5
                outer@ for (dy in -radius..radius step step) {
                    for (dx in -radius..radius step step) {
                        val cx = x + dx
                        val cy = y + dy
                        val cur = ScreenCaptureService.getPixelHex(cx, cy) ?: continue
                        lastSeenHex = cur
                        if (ScreenCaptureService.compareHex(cur, hex, tolerance)) {
                            matched = true
                            hitX = cx
                            hitY = cy
                            break@outer
                        }
                    }
                }
            }

            if (matched && autoTap) {
                FastAccessibilityService.tap(hitX, hitY)
            }

            Handler(Looper.getMainLooper()).post {
                val status = if (matched) "✅ COCOK (YES)" else "❌ BEDA (NO)"
                Toast.makeText(context, "Layar: $lastSeenHex | Target: $hex -> $status", Toast.LENGTH_SHORT).show()
            }

            pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            pendingResult.finish()
        }.start()
    }
}
