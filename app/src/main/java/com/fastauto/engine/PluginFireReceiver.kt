package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

        val targetRgb = ScreenCaptureService.parseHexToInt(hex)
        val ramReady = ScreenCaptureService.isReady()
        val stealthReady = FastAccessibilityService.instance != null

        if (!ramReady && !stealthReady) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR SUPER CEPAT (< 0.1 ms): Mesin RAM Aktif & Mode 0
        if (ramReady && mode == 0) {
            val actualRgb = ScreenCaptureService.getPixelRgbInt(x, y)
            val matched = actualRgb != null && ScreenCaptureService.compareRgbInt(actualRgb, targetRgb, tolerance)
            if (matched && autoTap) FastAccessibilityService.tap(x, y)
            resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR ASYNC (Mode Tunggu / Scan Area / Mode Siluman)
        val pendingResult = goAsync()
        Thread {
            var matched = false
            var hitX = x
            var hitY = y

            if (mode == 0) {
                val actualHex = FastAccessibilityService.getStealthPixelHex(x, y)
                if (actualHex != null) {
                    matched = ScreenCaptureService.compareRgbInt(
                        ScreenCaptureService.parseHexToInt(actualHex),
                        targetRgb,
                        tolerance
                    )
                }
            } else if (mode == 1) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeout) {
                    if (ScreenCaptureService.isReady()) {
                        val rgb = ScreenCaptureService.getPixelRgbInt(x, y)
                        if (rgb != null && ScreenCaptureService.compareRgbInt(rgb, targetRgb, tolerance)) {
                            matched = true
                            break
                        }
                        Thread.sleep(4L) // Polling 250 kali per detik di RAM
                    } else {
                        val actualHex = FastAccessibilityService.getStealthPixelHex(x, y)
                        if (actualHex != null && ScreenCaptureService.compareRgbInt(
                                ScreenCaptureService.parseHexToInt(actualHex),
                                targetRgb,
                                tolerance
                            )
                        ) {
                            matched = true
                            break
                        }
                        Thread.sleep(320L) // Cooldown aman untuk Android 11+ Accessibility Screenshot
                    }
                }
            } else if (mode == 2) {
                if (ScreenCaptureService.isReady()) {
                    val radius = 100
                    val step = 4
                    outer@ for (dy in -radius..radius step step) {
                        for (dx in -radius..radius step step) {
                            val cx = x + dx
                            val cy = y + dy
                            val rgb = ScreenCaptureService.getPixelRgbInt(cx, cy) ?: continue
                            if (ScreenCaptureService.compareRgbInt(rgb, targetRgb, tolerance)) {
                                matched = true; hitX = cx; hitY = cy; break@outer
                            }
                        }
                    }
                } else {
                    val hit = FastAccessibilityService.scanStealthArea(x, y, 100, 4, hex, tolerance)
                    if (hit != null) {
                        matched = true; hitX = hit.first; hitY = hit.second
                    }
                }
            }

            if (matched && autoTap) {
                FastAccessibilityService.tap(hitX, hitY)
            }

            pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            pendingResult.finish()
        }.start()
    }
}
