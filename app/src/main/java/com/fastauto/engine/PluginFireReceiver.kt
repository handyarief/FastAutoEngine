package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PluginFireReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"

        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17
        const val RESULT_CONDITION_UNKNOWN = 18

        private const val MAX_SAFE_TIMEOUT_MS = 5000L
        private const val MAX_BROADCAST_BUDGET_MS = 6500L

        // Thread pool ringan yang otomatis mematikan thread setelah 15 detik idle (0% CPU saat idle)
        private val workerExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
            2,
            2,
            15L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(16)
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUERY_CONDITION) return

        val isOrdered = isOrderedBroadcast

        val bundle = try {
            intent.getBundleExtra(EXTRA_BUNDLE)
        } catch (_: Throwable) {
            null
        }

        if (bundle == null) {
            if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
            return
        }

        // Cepat kembali tanpa membuat worker jika Mesin RAM belum dinyalakan
        if (!ScreenCaptureService.isReady()) {
            if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
            return
        }

        val x1 = bundle.getInt("x", -1)
        val y1 = bundle.getInt("y", -1)
        if (x1 < 0 || y1 < 0) {
            if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
            return
        }

        val targetRgb1 = if (bundle.containsKey("rgb1")) {
            bundle.getInt("rgb1") and 0xFFFFFF
        } else {
            ScreenCaptureService.parseHexToIntOrNull(bundle.getString("hex"))
                ?: run {
                    if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
                    return
                }
        }

        val useColor2 = bundle.getBoolean("useColor2", false)
        val colorLogic = bundle.getInt("colorLogic", ScreenCaptureService.LOGIC_AND)
        if (useColor2 && colorLogic != ScreenCaptureService.LOGIC_AND && colorLogic != ScreenCaptureService.LOGIC_OR) {
            if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
            return
        }

        var x2 = 0
        var y2 = 0
        var targetRgb2 = 0
        if (useColor2) {
            x2 = bundle.getInt("x2", -1)
            y2 = bundle.getInt("y2", -1)
            if (x2 < 0 || y2 < 0) {
                if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
                return
            }
            targetRgb2 = if (bundle.containsKey("rgb2")) {
                bundle.getInt("rgb2") and 0xFFFFFF
            } else {
                ScreenCaptureService.parseHexToIntOrNull(bundle.getString("hex2"))
                    ?: run {
                        if (isOrdered) resultCode = RESULT_CONDITION_UNKNOWN
                        return
                    }
            }
        }

        val radius = bundle.getInt("radius", 100).coerceIn(1, 500)
        val step = bundle.getInt("step", 4).coerceIn(1, 20)
        val tolerance = bundle.getInt("tolerance", 20).coerceIn(0, 255)
        val requestedTimeout = bundle.getLong("timeout", 0L).coerceIn(0L, MAX_SAFE_TIMEOUT_MS)

        val receiveStartNanos = System.nanoTime()
        val pendingResult = goAsync()

        try {
            workerExecutor.execute {
                var finalCode = RESULT_CONDITION_UNKNOWN
                try {
                    val elapsedMs = (System.nanoTime() - receiveStartNanos) / 1_000_000L
                    val remainingBudgetMs = MAX_BROADCAST_BUDGET_MS - elapsedMs

                    if (remainingBudgetMs > 100L) {
                        val effectiveTimeout = if (requestedTimeout > 0L) {
                            minOf(requestedTimeout, remainingBudgetMs - 100L)
                        } else {
                            0L
                        }

                        val eval = ScreenCaptureService.evaluateAreaOnDemand(
                            x1, y1, targetRgb1,
                            useColor2, colorLogic,
                            x2, y2, targetRgb2,
                            radius, step, tolerance,
                            effectiveTimeout
                        )

                        finalCode = when (eval) {
                            ScreenCaptureService.EvalResult.MATCHED -> RESULT_CONDITION_SATISFIED
                            ScreenCaptureService.EvalResult.NOT_MATCHED -> RESULT_CONDITION_UNSATISFIED
                            ScreenCaptureService.EvalResult.ENGINE_NOT_READY,
                            ScreenCaptureService.EvalResult.CAPTURE_FAILED -> RESULT_CONDITION_UNKNOWN
                        }
                    }
                } catch (_: Throwable) {
                    finalCode = RESULT_CONDITION_UNKNOWN
                } finally {
                    try {
                        if (isOrdered) {
                            pendingResult.resultCode = finalCode
                        }
                        pendingResult.finish()
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: RejectedExecutionException) {
            try {
                if (isOrdered) {
                    pendingResult.resultCode = RESULT_CONDITION_UNKNOWN
                }
                pendingResult.finish()
            } catch (_: Throwable) {}
        }
    }
}
