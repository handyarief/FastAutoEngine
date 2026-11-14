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
        val x1 = bundle.getInt("x", 500)
        val y1 = bundle.getInt("y", 500)
        val hex1 = bundle.getString("hex", "#FF0000") ?: "#FF0000"

        val useColor2 = bundle.getBoolean("useColor2", false)
        val colorLogic = bundle.getInt("colorLogic", 0) // 0 = AND, 1 = OR
        val x2 = bundle.getInt("x2", 500)
        val y2 = bundle.getInt("y2", 500)
        val hex2 = bundle.getString("hex2", "#00FF00") ?: "#00FF00"

        val tolerance = bundle.getInt("tolerance", 20)
        val timeout = bundle.getLong("timeout", 1000L)

        val targetRgb1 = ScreenCaptureService.parseHexToInt(hex1)
        val targetRgb2 = ScreenCaptureService.parseHexToInt(hex2)

        val ramReady = ScreenCaptureService.isReady()
        val stealthReady = FastAccessibilityService.instance != null

        if (!ramReady && !stealthReady) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR SUPER CEPAT (< 0.1 ms): Mesin RAM Aktif & Mode 0
        if (ramReady && mode == 0) {
            val matched = checkRamPointCondition(
                x1, y1, targetRgb1,
                useColor2, colorLogic,
                x2, y2, targetRgb2,
                tolerance
            )
            resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR ASYNC (Mode Tunggu / Cari Area / Mode Siluman)
        val pendingResult = goAsync()
        Thread {
            var matched = false

            if (mode == 0) {
                matched = checkStealthPointCondition(
                    x1, y1, targetRgb1,
                    useColor2, colorLogic,
                    x2, y2, targetRgb2,
                    tolerance
                )
            } else if (mode == 1) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeout) {
                    if (ScreenCaptureService.isReady()) {
                        if (checkRamPointCondition(
                                x1, y1, targetRgb1,
                                useColor2, colorLogic,
                                x2, y2, targetRgb2,
                                tolerance
                            )
                        ) {
                            matched = true
                            break
                        }
                        Thread.sleep(4L)
                    } else {
                        if (checkStealthPointCondition(
                                x1, y1, targetRgb1,
                                useColor2, colorLogic,
                                x2, y2, targetRgb2,
                                tolerance
                            )
                        ) {
                            matched = true
                            break
                        }
                        Thread.sleep(320L)
                    }
                }
            } else if (mode == 2) {
                if (ScreenCaptureService.isReady()) {
                    val m1 = scanRamArea(x1, y1, targetRgb1, tolerance)
                    matched = if (!useColor2) {
                        m1
                    } else if (colorLogic == 0) {
                        m1 && scanRamArea(x2, y2, targetRgb2, tolerance)
                    } else {
                        m1 || scanRamArea(x2, y2, targetRgb2, tolerance)
                    }
                } else {
                    val m1 = FastAccessibilityService.scanStealthArea(x1, y1, 100, 4, hex1, tolerance) != null
                    matched = if (!useColor2) {
                        m1
                    } else if (colorLogic == 0) {
                        m1 && (FastAccessibilityService.scanStealthArea(x2, y2, 100, 4, hex2, tolerance) != null)
                    } else {
                        m1 || (FastAccessibilityService.scanStealthArea(x2, y2, 100, 4, hex2, tolerance) != null)
                    }
                }
            }

            pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            pendingResult.finish()
        }.start()
    }

    private fun checkRamPointCondition(
        x1: Int, y1: Int, targetRgb1: Int,
        useColor2: Boolean, colorLogic: Int,
        x2: Int, y2: Int, targetRgb2: Int,
        tolerance: Int
    ): Boolean {
        val rgb1 = ScreenCaptureService.getPixelRgbInt(x1, y1)
        val m1 = rgb1 != null && ScreenCaptureService.compareRgbInt(rgb1, targetRgb1, tolerance)
        if (!useColor2) return m1

        val rgb2 = ScreenCaptureService.getPixelRgbInt(x2, y2)
        val m2 = rgb2 != null && ScreenCaptureService.compareRgbInt(rgb2, targetRgb2, tolerance)
        return if (colorLogic == 0) (m1 && m2) else (m1 || m2)
    }

    private fun checkStealthPointCondition(
        x1: Int, y1: Int, targetRgb1: Int,
        useColor2: Boolean, colorLogic: Int,
        x2: Int, y2: Int, targetRgb2: Int,
        tolerance: Int
    ): Boolean {
        val hex1 = FastAccessibilityService.getStealthPixelHex(x1, y1)
        val m1 = hex1 != null && ScreenCaptureService.compareRgbInt(
            ScreenCaptureService.parseHexToInt(hex1),
            targetRgb1,
            tolerance
        )
        if (!useColor2) return m1

        val hex2 = FastAccessibilityService.getStealthPixelHex(x2, y2)
        val m2 = hex2 != null && ScreenCaptureService.compareRgbInt(
            ScreenCaptureService.parseHexToInt(hex2),
            targetRgb2,
            tolerance
        )
        return if (colorLogic == 0) (m1 && m2) else (m1 || m2)
    }

    private fun scanRamArea(centerX: Int, centerY: Int, targetRgb: Int, tolerance: Int): Boolean {
        val radius = 100
        val step = 4
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                val rgb = ScreenCaptureService.getPixelRgbInt(centerX + dx, centerY + dy) ?: continue
                if (ScreenCaptureService.compareRgbInt(rgb, targetRgb, tolerance)) {
                    return true
                }
            }
        }
        return false
    }
}
