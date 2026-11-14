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
        val targetRgb2 = if (useColor2) ScreenCaptureService.parseHexToInt(hex2) else 0

        val ramReady = ScreenCaptureService.isReady()
        val stealthReady = FastAccessibilityService.instance != null

        if (!ramReady && !stealthReady) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR SUPER CEPAT (< 0.05 ms Tanpa Alokasi Thread): Mesin RAM Aktif pada Mode 0 atau Mode 2
        if (ramReady && (mode == 0 || mode == 2)) {
            val matched = if (mode == 0) {
                checkRamPointCondition(
                    x1, y1, targetRgb1,
                    useColor2, colorLogic,
                    x2, y2, targetRgb2,
                    tolerance
                )
            } else {
                checkRamAreaCondition(
                    x1, y1, targetRgb1,
                    useColor2, colorLogic,
                    x2, y2, targetRgb2,
                    tolerance
                )
            }
            resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            return
        }

        // JALUR ASYNC (Khusus Mode 1 Tunggu Warna, atau Mode Siluman Tanpa MediaProjection)
        val pendingResult = goAsync()
        Thread {
            var matched = false
            try {
                when (mode) {
                    0 -> {
                        matched = checkStealthPointCondition(
                            x1, y1, targetRgb1,
                            useColor2, colorLogic,
                            x2, y2, targetRgb2,
                            tolerance
                        )
                    }
                    1 -> {
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
                                Thread.sleep(250L)
                            }
                        }
                    }
                    2 -> {
                        matched = if (ScreenCaptureService.isReady()) {
                            checkRamAreaCondition(
                                x1, y1, targetRgb1,
                                useColor2, colorLogic,
                                x2, y2, targetRgb2,
                                tolerance
                            )
                        } else {
                            val m1 = FastAccessibilityService.scanStealthArea(x1, y1, 100, 4, hex1, tolerance) != null
                            if (!useColor2) {
                                m1
                            } else if (colorLogic == 0) {
                                m1 && (FastAccessibilityService.scanStealthArea(x2, y2, 100, 4, hex2, tolerance) != null)
                            } else {
                                m1 || (FastAccessibilityService.scanStealthArea(x2, y2, 100, 4, hex2, tolerance) != null)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
                pendingResult.finish()
            }
        }.start()
    }

    private fun checkRamPointCondition(
        x1: Int, y1: Int, targetRgb1: Int,
        useColor2: Boolean, colorLogic: Int,
        x2: Int, y2: Int, targetRgb2: Int,
        tolerance: Int
    ): Boolean {
        if (!useColor2) {
            val rgb1 = ScreenCaptureService.getPixelRgbInt(x1, y1) ?: return false
            return ScreenCaptureService.compareRgbInt(rgb1, targetRgb1, tolerance)
        }

        // Ambil 2 piksel sekaligus dalam 1x Lock & 1x Tarik Frame
        val (rgb1, rgb2) = ScreenCaptureService.getTwoPixelsRgbInt(x1, y1, x2, y2)
        val m1 = rgb1 != null && ScreenCaptureService.compareRgbInt(rgb1, targetRgb1, tolerance)
        if (colorLogic == 0 && !m1) return false // Short-circuit AND
        if (colorLogic == 1 && m1) return true   // Short-circuit OR

        val m2 = rgb2 != null && ScreenCaptureService.compareRgbInt(rgb2, targetRgb2, tolerance)
        return m2
    }

    private fun checkRamAreaCondition(
        x1: Int, y1: Int, targetRgb1: Int,
        useColor2: Boolean, colorLogic: Int,
        x2: Int, y2: Int, targetRgb2: Int,
        tolerance: Int
    ): Boolean {
        val m1 = ScreenCaptureService.scanRamAreaFast(x1, y1, 100, 4, targetRgb1, tolerance)
        if (!useColor2) return m1
        if (colorLogic == 0 && !m1) return false
        if (colorLogic == 1 && m1) return true

        return ScreenCaptureService.scanRamAreaFast(x2, y2, 100, 4, targetRgb2, tolerance)
    }

    private fun checkStealthPointCondition(
        x1: Int, y1: Int, targetRgb1: Int,
        useColor2: Boolean, colorLogic: Int,
        x2: Int, y2: Int, targetRgb2: Int,
        tolerance: Int
    ): Boolean {
        if (!useColor2) {
            val rgb1 = FastAccessibilityService.getStealthPixelRgbInt(x1, y1) ?: return false
            return ScreenCaptureService.compareRgbInt(rgb1, targetRgb1, tolerance)
        }

        // Ambil 2 piksel sekaligus hanya dengan 1x Screenshot
        val (rgb1, rgb2) = FastAccessibilityService.getStealthTwoPixelsRgbInt(x1, y1, x2, y2)
        val m1 = rgb1 != null && ScreenCaptureService.compareRgbInt(rgb1, targetRgb1, tolerance)
        val m2 = rgb2 != null && ScreenCaptureService.compareRgbInt(rgb2, targetRgb2, tolerance)
        return if (colorLogic == 0) (m1 && m2) else (m1 || m2)
    }
}
