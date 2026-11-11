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

        val ramReady = ScreenCaptureService.isReady()
        val stealthReady = FastAccessibilityService.instance != null

        // Jika Mesin RAM mati DAN Aksesibilitas juga mati, beri peringatan
        if (!ramReady && !stealthReady) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ Aktifkan Aksesibilitas (No. 3) untuk Mode Siluman!", Toast.LENGTH_SHORT).show()
            }
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR CEPAT 1: Jika Mesin RAM menyala & Mode 0
        if (ramReady && mode == 0) {
            val actualHex = ScreenCaptureService.getPixelHex(x, y) ?: "#000000"
            val matched = ScreenCaptureService.compareHex(actualHex, hex, tolerance)
            if (matched && autoTap) FastAccessibilityService.tap(x, y)

            Handler(Looper.getMainLooper()).post {
                val status = if (matched) "✅ COCOK" else "❌ BEDA"
                Toast.makeText(context, "[RAM] Layar: $actualHex | Target: $hex -> $status", Toast.LENGTH_SHORT).show()
            }
            resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR SILUMAN (STEALTH) ATAU MODE TUNGGU/SCAN AREA
        val pendingResult = goAsync()
        Thread {
            var matched = false
            var hitX = x
            var hitY = y
            var lastSeenHex = "#000000"

            if (mode == 0) {
                // Mode 0 via Siluman (~0.05 detik)
                lastSeenHex = FastAccessibilityService.getStealthPixelHex(x, y) ?: "#000000"
                matched = ScreenCaptureService.compareHex(lastSeenHex, hex, tolerance)
            } else if (mode == 1) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeout) {
                    lastSeenHex = if (ScreenCaptureService.isReady()) {
                        ScreenCaptureService.getPixelHex(x, y) ?: "#000000"
                    } else {
                        FastAccessibilityService.getStealthPixelHex(x, y) ?: "#000000"
                    }
                    if (ScreenCaptureService.compareHex(lastSeenHex, hex, tolerance)) {
                        matched = true
                        break
                    }
                    Thread.sleep(if (ScreenCaptureService.isReady()) 10L else 320L)
                }
            } else if (mode == 2) {
                if (ScreenCaptureService.isReady()) {
                    val radius = 100
                    val step = 5
                    outer@ for (dy in -radius..radius step step) {
                        for (dx in -radius..radius step step) {
                            val cx = x + dx
                            val cy = y + dy
                            val cur = ScreenCaptureService.getPixelHex(cx, cy) ?: continue
                            lastSeenHex = cur
                            if (ScreenCaptureService.compareHex(cur, hex, tolerance)) {
                                matched = true; hitX = cx; hitY = cy; break@outer
                            }
                        }
                    }
                } else {
                    val hit = FastAccessibilityService.scanStealthArea(x, y, 100, 5, hex, tolerance)
                    if (hit != null) {
                        matched = true; hitX = hit.first; hitY = hit.second; lastSeenHex = hex
                    }
                }
            }

            if (matched && autoTap) {
                FastAccessibilityService.tap(hitX, hitY)
            }

            Handler(Looper.getMainLooper()).post {
                val modeTag = if (ramReady) "[RAM]" else "[SILUMAN]"
                val status = if (matched) "✅ COCOK" else "❌ BEDA"
                Toast.makeText(context, "$modeTag Layar: $lastSeenHex | Target: $hex -> $status", Toast.LENGTH_SHORT).show()
            }

            pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            pendingResult.finish()
        }.start()
    }
}
