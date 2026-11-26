package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class PluginFireReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"

        const val EXTRA_VARIABLE_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
        const val EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17
        const val RESULT_CONDITION_UNKNOWN = 18

        const val EXTRA_OUT_STATUS = "com.fastauto.engine.extra.STATUS"
        const val EXTRA_OUT_DETAIL = "com.fastauto.engine.extra.DETAIL"
        const val EXTRA_OUT_MATCH_COUNT = "com.fastauto.engine.extra.MATCH_COUNT"

        private val oneShotExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FastOneShotWorker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
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
            finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "MISSING_BUNDLE", 0)
            return
        }

        if (!ScreenCaptureService.isReady()) {
            finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "ENGINE_NOT_READY", 0)
            return
        }

        val colorMode = bundle.getInt("colorMode", ScreenCaptureService.COLOR_MODE_SINGLE)
        if (colorMode != ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS &&
            colorMode != ScreenCaptureService.COLOR_MODE_SINGLE &&
            colorMode != ScreenCaptureService.COLOR_MODE_PICK_ONE
        ) {
            finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_COLOR_MODE", 0)
            return
        }

        val pickOneTarget = bundle.getInt("pickOneTarget", ScreenCaptureService.PICK_ONE_COLOR_1)
        if (colorMode == ScreenCaptureService.COLOR_MODE_PICK_ONE &&
            pickOneTarget != ScreenCaptureService.PICK_ONE_COLOR_1 &&
            pickOneTarget != ScreenCaptureService.PICK_ONE_COLOR_2
        ) {
            finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_PICK_ONE_TARGET", 0)
            return
        }

        val useColor1 = when (colorMode) {
            ScreenCaptureService.COLOR_MODE_SINGLE -> true
            ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> true
            ScreenCaptureService.COLOR_MODE_PICK_ONE -> pickOneTarget == ScreenCaptureService.PICK_ONE_COLOR_1
            else -> false
        }
        val useColor2 = when (colorMode) {
            ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> true
            ScreenCaptureService.COLOR_MODE_PICK_ONE -> pickOneTarget == ScreenCaptureService.PICK_ONE_COLOR_2
            else -> false
        }

        var x1 = 0
        var y1 = 0
        var rgb1 = 0
        if (useColor1) {
            x1 = bundle.getInt("x", -1)
            y1 = bundle.getInt("y", -1)
            if (x1 < 0 || y1 < 0) {
                finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_COORD_1", 0)
                return
            }
            val parsed1 = if (bundle.containsKey("rgb1")) {
                bundle.getInt("rgb1") and 0xFFFFFF
            } else {
                ScreenCaptureService.parseHexToIntOrNull(bundle.getString("hex"))
            }
            if (parsed1 == null) {
                finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_HEX_1", 0)
                return
            }
            rgb1 = parsed1
        }

        var x2 = 0
        var y2 = 0
        var rgb2 = 0
        if (useColor2) {
            x2 = bundle.getInt("x2", -1)
            y2 = bundle.getInt("y2", -1)
            if (x2 < 0 || y2 < 0) {
                finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_COORD_2", 0)
                return
            }
            val parsed2 = if (bundle.containsKey("rgb2")) {
                bundle.getInt("rgb2") and 0xFFFFFF
            } else {
                ScreenCaptureService.parseHexToIntOrNull(bundle.getString("hex2"))
            }
            if (parsed2 == null) {
                finishWithResult(isOrdered, null, RESULT_CONDITION_UNKNOWN, "UNKNOWN", "INVALID_HEX_2", 0)
                return
            }
            rgb2 = parsed2
        }

        val colorLogic = bundle.getInt("colorLogic", ScreenCaptureService.LOGIC_AND)
        val refWidth = bundle.getInt("refWidth", 0)
        val refHeight = bundle.getInt("refHeight", 0)
        val refRotation = bundle.getInt("refRotation", 0)

        val radius = bundle.getInt("radius", 40).coerceIn(1, 600)
        val step = bundle.getInt("step", 2).coerceIn(1, 24)
        val tolerance = bundle.getInt("tolerance", 20).coerceIn(0, 255)
        val minMatches = bundle.getInt("minMatches", 1).coerceIn(1, 500)

        val pendingResult = goAsync()

        try {
            oneShotExecutor.execute {
                var code = RESULT_CONDITION_UNKNOWN
                var status = "UNKNOWN"
                var detail = "UNEXPECTED_ERROR"
                var matchCount = 0

                try {
                    val outcome = ScreenCaptureService.evaluateOneShot(
                        colorMode = colorMode,
                        pickOneTarget = pickOneTarget,
                        colorLogic = colorLogic,
                        x1 = x1, y1 = y1, rgb1 = rgb1,
                        x2 = x2, y2 = y2, rgb2 = rgb2,
                        refWidth = refWidth, refHeight = refHeight, refRotation = refRotation,
                        radius = radius, step = step, tolerance = tolerance, minMatches = minMatches
                    )
                    matchCount = outcome.matchedCount
                    detail = outcome.detail

                    when (outcome.status) {
                        ScreenCaptureService.EvalStatus.MATCHED -> {
                            code = RESULT_CONDITION_SATISFIED
                            status = "SATISFIED"
                        }
                        ScreenCaptureService.EvalStatus.NOT_MATCHED -> {
                            code = RESULT_CONDITION_UNSATISFIED
                            status = "UNSATISFIED"
                        }
                        ScreenCaptureService.EvalStatus.ERR_ENGINE_NOT_READY,
                        ScreenCaptureService.EvalStatus.ERR_CAPTURE_FAILED,
                        ScreenCaptureService.EvalStatus.ERR_INVALID_CONFIG -> {
                            code = RESULT_CONDITION_UNKNOWN
                            status = "UNKNOWN"
                        }
                    }
                } catch (_: Throwable) {
                    code = RESULT_CONDITION_UNKNOWN
                    status = "UNKNOWN"
                    detail = "EXCEPTION"
                } finally {
                    finishWithResult(
                        isOrdered = isOrdered,
                        pendingResult = pendingResult,
                        code = code,
                        status = status,
                        detail = detail,
                        matchCount = matchCount
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            finishWithResult(
                isOrdered = isOrdered,
                pendingResult = pendingResult,
                code = RESULT_CONDITION_UNKNOWN,
                status = "UNKNOWN",
                detail = "EXECUTOR_BUSY",
                matchCount = 0
            )
        }
    }

    private fun finishWithResult(
        isOrdered: Boolean,
        pendingResult: PendingResult?,
        code: Int,
        status: String,
        detail: String,
        matchCount: Int
    ) {
        val taskerVars = Bundle().apply {
            putString("%fastauto_status", status)
            putString("%fastauto_detail", detail)
            putString("%fastauto_match_count", matchCount.toString())
        }
        val extras = Bundle().apply {
            putString(EXTRA_OUT_STATUS, status)
            putString(EXTRA_OUT_DETAIL, detail)
            putInt(EXTRA_OUT_MATCH_COUNT, matchCount)
            putBundle(EXTRA_VARIABLE_BUNDLE, taskerVars)
        }
        try {
            if (isOrdered) {
                if (pendingResult != null) {
                    pendingResult.resultCode = code
                    try {
                        pendingResult.setResultExtras(extras)
                    } catch (_: Throwable) {
                        setResultExtras(extras)
                    }
                } else {
                    resultCode = code
                    setResultExtras(extras)
                }
            }
        } catch (_: Throwable) {}

        try {
            pendingResult?.finish()
        } catch (_: Throwable) {}
    }
}
