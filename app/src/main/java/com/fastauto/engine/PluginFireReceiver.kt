package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PluginFireReceiver : BroadcastReceiver() {

    companion object {
        // Standar kode hasil untuk blok Plug-in Decision di Automate (YES / NO)
        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE") ?: return
        val mode = bundle.getInt("mode", 0)
        val x = bundle.getInt("x", 500)
        val y = bundle.getInt("y", 500)
        val hex = bundle.getString("hex", "#FF0000") ?: "#FF0000"
        val tolerance = bundle.getInt("tolerance", 20)
        val timeout = bundle.getLong("timeout", 5000L)
        val autoTap = bundle.getBoolean("autoTap", false)

        val pendingResult = goAsync()

        Thread {
            var matched = false
            var hitX = x
            var hitY = y

            when (mode) {
                0 -> {
                    // Mode 0: Cek Instan (< 2ms)
                    matched = ScreenCaptureService.matchColor(x, y, hex, tolerance)
                }
                1 -> {
                    // Mode 1: Tunggu sampai warna muncul dengan kecepatan tinggi
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < timeout) {
                        if (ScreenCaptureService.matchColor(x, y, hex, tolerance)) {
                            matched = true
                            break
                        }
                        Thread.sleep(2) // Cek tiap 2 milidetik!
                    }
                }
                2 -> {
                    // Mode 2: Scan area sekitar (±100 piksel) untuk warna yang bergeser
                    val radius = 100
                    val step = 5
                    outer@ for (dy in -radius..radius step step) {
                        for (dx in -radius..radius step step) {
                            val cx = x + dx
                            val cy = y + dy
                            if (ScreenCaptureService.matchColor(cx, cy, hex, tolerance)) {
                                matched = true
                                hitX = cx
                                hitY = cy
                                break@outer
                            }
                        }
                    }
                }
            }

            if (matched && autoTap) {
                FastAccessibilityService.tap(hitX, hitY)
            }

            // Kirim hasil cabang YES (16) atau NO (17) kembali ke Automate
            pendingResult.resultCode = if (matched) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
            pendingResult.finish()
        }.start()
    }
}
