package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

class PluginFireReceiver : BroadcastReceiver() {

    companion object {
        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17

        // Satu Reusable Thread Statis khusus untuk mode timeout > 0 (Tanpa alokasi Thread baru)
        private val workerExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")
        if (bundle == null) {
            if (isOrderedBroadcast) resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        val x1 = bundle.getInt("x", 540)
        val y1 = bundle.getInt("y", 1200)
        val targetRgb1 = if (bundle.containsKey("rgb1")) {
            bundle.getInt("rgb1")
        } else {
            ScreenCaptureService.parseHexToInt(bundle.getString("hex", "#FF0000") ?: "#FF0000")
        }

        val useColor2 = bundle.getBoolean("useColor2", false)
        val colorLogic = bundle.getInt("colorLogic", 0) // 0 = AND, 1 = OR
        val x2 = bundle.getInt("x2", 540)
        val y2 = bundle.getInt("y2", 1200)
        val targetRgb2 = if (!useColor2) {
            0
        } else if (bundle.containsKey("rgb2")) {
            bundle.getInt("rgb2")
        } else {
            ScreenCaptureService.parseHexToInt(bundle.getString("hex2", "#00FF00") ?: "#00FF00")
        }

        val radius = bundle.getInt("radius", 100)
        val step = bundle.getInt("step", 4)
        val tolerance = bundle.getInt("tolerance", 20)
        val timeout = bundle.getLong("timeout", 0L)

        val ramReady = ScreenCaptureService.isReady()

        // JALUR SUPER CEPAT Murni On-Demand Tanpa Thread saat Mesin RAM Siap & Timeout <= 0
        if (ramReady && timeout <= 0L) {
            val matched = ScreenCaptureService.evaluateAreaInstantOrWait(
                x1, y1, targetRgb1,
                useColor2, colorLogic,
                x2, y2, targetRgb2,
                radius, step, tolerance,
                0L
            )
            if (isOrderedBroadcast) {
                resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            }
            return
        }

        val stealthReady = FastAccessibilityService.instance != null
        if (!ramReady && !stealthReady) {
            if (isOrderedBroadcast) resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR TUNGGU FRAME-SYNC ATAU STEALTH CADANGAN (Memakai Reusable Executor)
        val isOrdered = isOrderedBroadcast
        val pendingResult = goAsync()
        workerExecutor.execute {
            var matched = false
            try {
                if (ScreenCaptureService.isReady()) {
                    matched = ScreenCaptureService.evaluateAreaInstantOrWait(
                        x1, y1, targetRgb1,
                        useColor2, colorLogic,
                        x2, y2, targetRgb2,
                        radius, step, tolerance,
                        timeout
                    )
                } else {
                    val waitBudget = timeout.coerceIn(0L, 8000L)
                    val startTime = System.currentTimeMillis()
                    do {
                        val m1 = FastAccessibilityService.scanStealthAreaRgb(
                            x1, y1, radius, step, targetRgb1, tolerance
                        )
                        matched = if (!useColor2) {
                            m1
                        } else if (colorLogic == 0) {
                            m1 && FastAccessibilityService.scanStealthAreaRgb(
                                x2, y2, radius, step, targetRgb2, tolerance
                            )
                        } else {
                            m1 || FastAccessibilityService.scanStealthAreaRgb(
                                x2, y2, radius, step, targetRgb2, tolerance
                            )
                        }
                        if (matched || waitBudget <= 0L) break
                        Thread.sleep(150L)
                    } while (System.currentTimeMillis() - startTime < waitBudget)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    if (isOrdered) {
                        pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
                    }
                    pendingResult.finish()
                } catch (_: Throwable) {}
            }
        }
    }
}
