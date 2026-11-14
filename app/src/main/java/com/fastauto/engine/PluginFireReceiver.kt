
package com.fastauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PluginFireReceiver : BroadcastReceiver() {

    companion object {

        const val RESULT_CONDITION_SATISFIED = 16
        const val RESULT_CONDITION_UNSATISFIED = 17

        private const val TAG = "FastAutoDecision"

        private const val MAX_FRAME_AGE_MS = 1000L
        private const val MAX_WAIT_MS = 8000L

        private const val FRAME_POLL_MS = 8L
        private const val ACCESSIBILITY_POLL_MS = 320L

        private const val AREA_RADIUS = 100
        private const val AREA_STEP = 4

        /*
         * Maksimal 2 pekerjaan sekaligus.
         * Antrean dibatasi untuk mencegah penumpukan pekerjaan.
         */
        private val workerExecutor = ThreadPoolExecutor(
            2,
            2,
            15L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(4),
            ThreadFactory { runnable ->
                Thread(
                    runnable,
                    "FastAutoPluginWorker"
                ).apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private data class ColorConfig(
        val mode: Int,
        val x1: Int,
        val y1: Int,
        val targetRgb1: Int,
        val useColor2: Boolean,
        val colorLogic: Int,
        val x2: Int,
        val y2: Int,
        val targetRgb2: Int,
        val tolerance: Int,
        val timeout: Long
    )

    private data class AreaPlan(
        val coordinates: IntArray,
        val firstCount: Int,
        val secondStart: Int,
        val secondCount: Int
    )

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val bundle = intent.getBundleExtra(
            "com.twofortyfouram.locale.intent.extra.BUNDLE"
        )

        if (bundle == null) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        val config = readConfig(bundle)

        if (config == null) {
            resultCode = RESULT_CONDITION_UNSATISFIED
            return
        }

        /*
         * JALUR UTAMA:
         * Mode instan membaca warna langsung dari RAM.
         * Tidak membuat Thread, screenshot baru,
         * Bitmap, atau Accessibility Screenshot.
         */
        if (
            config.mode == 0 &&
            ScreenCaptureService.isFrameFresh(MAX_FRAME_AGE_MS)
        ) {

            val matched = checkRamPointCondition(config)

            resultCode = if (matched) {
                RESULT_CONDITION_SATISFIED
            } else {
                RESULT_CONDITION_UNSATISFIED
            }

            return
        }

        /*
         * Mode lain atau jalur fallback dijalankan asynchronous.
         */
        val pendingResult = goAsync()

        try {

            workerExecutor.execute {

                try {

                    val matched = performCheck(config)

                    pendingResult.resultCode = if (matched) {
                        RESULT_CONDITION_SATISFIED
                    } else {
                        RESULT_CONDITION_UNSATISFIED
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Pemeriksaan warna gagal",
                        e
                    )

                    pendingResult.resultCode =
                        RESULT_CONDITION_UNSATISFIED

                } finally {

                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal menyelesaikan hasil", e)
                    }
                }
            }

        } catch (e: RejectedExecutionException) {

            Log.w(TAG, "Antrean pemeriksaan penuh")

            pendingResult.resultCode =
                RESULT_CONDITION_UNSATISFIED

            try {
                pendingResult.finish()
            } catch (_: Exception) {
            }
        }
    }

    /*
     * Membaca konfigurasi sesuai key asli PluginConfigActivity.
     * Tidak mengubah format Bundle Automate.
     */
    private fun readConfig(
        bundle: android.os.Bundle
    ): ColorConfig? {

        val mode = bundle.getInt("mode", 0)

        val x1 = bundle.getInt("x", 500)
        val y1 = bundle.getInt("y", 500)

        val hex1 = bundle.getString("hex")
            ?: "#FF0000"

        val useColor2 = bundle.getBoolean(
            "useColor2",
            false
        )

        val colorLogic = bundle.getInt(
            "colorLogic",
            0
        )

        val x2 = bundle.getInt("x2", 500)
        val y2 = bundle.getInt("y2", 500)

        val hex2 = bundle.getString("hex2")
            ?: "#00FF00"

        val tolerance = bundle.getInt(
            "tolerance",
            20
        )

        val timeout = bundle.getLong(
            "timeout",
            1000L
        )

        if (mode !in 0..2) return null

        if (colorLogic !in 0..1) return null

        if (tolerance !in 0..100) return null

        val target1 =
            ScreenCaptureService.parseHexToIntOrNull(hex1)
                ?: return null

        val target2 =
            ScreenCaptureService.parseHexToIntOrNull(hex2)
                ?: return null

        return ColorConfig(
            mode = mode,
            x1 = x1,
            y1 = y1,
            targetRgb1 = target1,
            useColor2 = useColor2,
            colorLogic = colorLogic,
            x2 = x2,
            y2 = y2,
            targetRgb2 = target2,
            tolerance = tolerance,
            timeout = timeout.coerceIn(1L, MAX_WAIT_MS)
        )
    }

    private fun performCheck(
        config: ColorConfig
    ): Boolean {

        return when (config.mode) {

            0 -> checkInstant(config)

            1 -> waitForColor(config)

            2 -> checkArea(config)

            else -> false
        }
    }

    /*
     * MODE 0: INSTANT CHECK
     */
    private fun checkInstant(
        config: ColorConfig
    ): Boolean {

        if (ScreenCaptureService.isFrameFresh(MAX_FRAME_AGE_MS)) {
            return checkRamPointCondition(config)
        }

        // Fallback hanya jika RAM capture tidak tersedia/terlalu lama.
        return if (FastAccessibilityService.instance != null) {
            checkStealthPointCondition(config)
        } else {
            false
        }
    }

    /*
     * PEMERIKSAAN TITIK RAM
     *
     * Semua koordinat dibaca melalui satu PixelBatch.
     * Dua target warna berasal dari frame yang sama.
     */
    private fun checkRamPointCondition(
        config: ColorConfig
    ): Boolean {

        val coordinates = if (config.useColor2) {

            intArrayOf(
                config.x1,
                config.y1,
                config.x2,
                config.y2
            )

        } else {

            intArrayOf(
                config.x1,
                config.y1
            )
        }

        val batch = ScreenCaptureService.getPixelRgbBatch(
            coordinates
        ) ?: return false

        if (batch.receivedAgeMs > MAX_FRAME_AGE_MS) {
            return false
        }

        val rgb1 = batch.rgbValues.getOrNull(0)
            ?: return false

        val match1 = ScreenCaptureService.compareRgbInt(
            rgb1,
            config.targetRgb1,
            config.tolerance
        )

        if (!config.useColor2) {
            return match1
        }

        // AND bisa berhenti lebih awal.
        if (config.colorLogic == 0 && !match1) {
            return false
        }

        // OR bisa berhenti lebih awal.
        if (config.colorLogic == 1 && match1) {
            return true
        }

        val rgb2 = batch.rgbValues.getOrNull(1)
            ?: return false

        val match2 = ScreenCaptureService.compareRgbInt(
            rgb2,
            config.targetRgb2,
            config.tolerance
        )

        return if (config.colorLogic == 0) {
            match1 && match2
        } else {
            match1 || match2
        }
    }

    /*
     * MODE 1: WAIT FOR COLOR
     *
     * Memeriksa hanya ketika frame baru tersedia.
     * Tidak membaca piksel berulang kali dari frame yang sama.
     */
    private fun waitForColor(
        config: ColorConfig
    ): Boolean {

        val startTime = SystemClock.elapsedRealtime()

        var lastSequence = Long.MIN_VALUE
        var nextAccessibilityCheck = 0L

        while (
            SystemClock.elapsedRealtime() - startTime <
            config.timeout
        ) {

            if (Thread.currentThread().isInterrupted) {
                return false
            }

            val now = SystemClock.elapsedRealtime()

            val frameInfo = ScreenCaptureService.getFrameInfo()

            if (
                frameInfo != null &&
                frameInfo.receivedAgeMs <= MAX_FRAME_AGE_MS
            ) {

                if (frameInfo.sequence != lastSequence) {

                    val coordinates = if (config.useColor2) {
                        intArrayOf(
                            config.x1,
                            config.y1,
                            config.x2,
                            config.y2
                        )
                    } else {
                        intArrayOf(
                            config.x1,
                            config.y1
                        )
                    }

                    val batch =
                        ScreenCaptureService.getPixelRgbBatch(
                            coordinates
                        )

                    if (batch != null) {

                        lastSequence = batch.frameSequence

                        if (
                            batch.receivedAgeMs <= MAX_FRAME_AGE_MS &&
                            evaluateBatch(batch, config)
                        ) {
                            return true
                        }

                    } else {

                        lastSequence = frameInfo.sequence
                    }
                }

            } else {

                /*
                 * Jalur cadangan ketika frame RAM tidak tersedia
                 * atau sudah terlalu lama.
                 *
                 * Tidak mengambil screenshot setiap 8 ms.
                 */
                if (
                    FastAccessibilityService.instance != null &&
                    now >= nextAccessibilityCheck
                ) {

                    if (checkStealthPointCondition(config)) {
                        return true
                    }

                    nextAccessibilityCheck =
                        now + ACCESSIBILITY_POLL_MS
                }
            }

            if (!sleepSafely(FRAME_POLL_MS)) {
                return false
            }
        }

        return false
    }

    /*
     * Evaluasi hasil batch.
     */
    private fun evaluateBatch(
        batch: ScreenCaptureService.PixelBatch,
        config: ColorConfig
    ): Boolean {

        if (batch.receivedAgeMs > MAX_FRAME_AGE_MS) {
            return false
        }

        val rgb1 = batch.rgbValues.getOrNull(0)
            ?: return false

        val match1 = ScreenCaptureService.compareRgbInt(
            rgb1,
            config.targetRgb1,
            config.tolerance
        )

        if (!config.useColor2) {
            return match1
        }

        if (config.colorLogic == 0 && !match1) {
            return false
        }

        if (config.colorLogic == 1 && match1) {
            return true
        }

        val rgb2 = batch.rgbValues.getOrNull(1)
            ?: return false

        val match2 = ScreenCaptureService.compareRgbInt(
            rgb2,
            config.targetRgb2,
            config.tolerance
        )

        return if (config.colorLogic == 0) {
            match1 && match2
        } else {
            match1 || match2
        }
    }

    /*
     * MODE 2: AREA SCAN
     *
     * Pemeriksaan semua titik area memakai satu frame.
     * Tidak membuat screenshot baru setiap koordinat.
     */
    private fun checkArea(
        config: ColorConfig
    ): Boolean {

        if (ScreenCaptureService.isFrameFresh(MAX_FRAME_AGE_MS)) {
            return checkRamArea(config)
        }

        return if (FastAccessibilityService.instance != null) {
            checkStealthArea(config)
        } else {
            false
        }
    }

    /*
     * Membuat koordinat pemindaian area.
     */
    private fun createGrid(
        centerX: Int,
        centerY: Int,
        radius: Int,
        step: Int,
        width: Int,
        height: Int
    ): IntArray {

        if (width <= 0 || height <= 0 || radius < 0) {
            return IntArray(0)
        }

        val left = (
            centerX.toLong() - radius
        ).coerceAtLeast(0L).coerceAtMost(
            (width - 1).toLong()
        ).toInt()

        val top = (
            centerY.toLong() - radius
        ).coerceAtLeast(0L).coerceAtMost(
            (height - 1).toLong()
        ).toInt()

        val right = (
            centerX.toLong() + radius
        ).coerceAtMost(
            (width - 1).toLong()
        ).toInt()

        val bottom = (
            centerY.toLong() + radius
        ).coerceAtMost(
            (height - 1).toLong()
        ).toInt()

        if (left > right || top > bottom) {
            return IntArray(0)
        }

        val safeStep = step.coerceAtLeast(1)

        val xCount = (right - left) / safeStep + 1
        val yCount = (bottom - top) / safeStep + 1

        val coordinates = IntArray(
            xCount * yCount * 2
        )

        var index = 0

        for (y in top..bottom step safeStep) {

            for (x in left..right step safeStep) {

                coordinates[index++] = x
                coordinates[index++] = y
            }
        }

        return coordinates
    }

    /*
     * Membuat satu rencana scan untuk satu/dua warna.
     */
    private fun buildAreaPlan(
        config: ColorConfig,
        width: Int,
        height: Int
    ): AreaPlan? {

        val first = createGrid(
            config.x1,
            config.y1,
            AREA_RADIUS,
            AREA_STEP,
            width,
            height
        )

        if (first.isEmpty()) return null

        val second = if (config.useColor2) {

            createGrid(
                config.x2,
                config.y2,
                AREA_RADIUS,
                AREA_STEP,
                width,
                height
            )

        } else {

            IntArray(0)
        }

        if (config.useColor2 && second.isEmpty()) {
            return null
        }

        val coordinates = IntArray(
            first.size + second.size
        )

        first.copyInto(coordinates)

        if (second.isNotEmpty()) {
            second.copyInto(
                coordinates,
                destinationOffset = first.size
            )
        }

        return AreaPlan(
            coordinates = coordinates,
            firstCount = first.size / 2,
            secondStart = first.size / 2,
            secondCount = second.size / 2
        )
    }

    /*
     * Memeriksa apakah warna ditemukan di bagian batch tertentu.
     */
    private fun findMatchInBatch(
        values: IntArray,
        start: Int,
        count: Int,
        targetRgb: Int,
        tolerance: Int
    ): Boolean {

        val end = (start + count).coerceAtMost(
            values.size
        )

        for (index in start until end) {

            if (
                ScreenCaptureService.compareRgbInt(
                    values[index],
                    targetRgb,
                    tolerance
                )
            ) {
                return true
            }
        }

        return false
    }

    /*
     * RAM AREA SCAN:
     * Satu frame untuk kedua target.
     */
    private fun checkRamArea(
        config: ColorConfig
    ): Boolean {

        repeat(2) {

            val info = ScreenCaptureService.getFrameInfo()
                ?: return false

            if (info.receivedAgeMs > MAX_FRAME_AGE_MS) {
                return false
            }

            val plan = buildAreaPlan(
                config,
                info.width,
                info.height
            ) ?: return false

            val batch =
                ScreenCaptureService.getPixelRgbBatch(
                    plan.coordinates
                ) ?: return false

            // Jika ukuran berubah akibat rotasi, ulangi sekali.
            if (
                batch.width != info.width ||
                batch.height != info.height
            ) {
                return@repeat
            }

            if (batch.receivedAgeMs > MAX_FRAME_AGE_MS) {
                return false
            }

            val match1 = findMatchInBatch(
                batch.rgbValues,
                0,
                plan.firstCount,
                config.targetRgb1,
                config.tolerance
            )

            if (!config.useColor2) {
                return match1
            }

            if (config.colorLogic == 0 && !match1) {
                return false
            }

            if (config.colorLogic == 1 && match1) {
                return true
            }

            val match2 = findMatchInBatch(
                batch.rgbValues,
                plan.secondStart,
                plan.secondCount,
                config.targetRgb2,
                config.tolerance
            )

            return if (config.colorLogic == 0) {
                match1 && match2
            } else {
                match1 || match2
            }
        }

        return false
    }

    /*
     * FALLBACK ACCESSIBILITY:
     * Digunakan ketika RAM capture tidak tersedia.
     */
    private fun checkStealthPointCondition(
        config: ColorConfig
    ): Boolean {

        val actualHex1 =
            FastAccessibilityService.getStealthPixelHex(
                config.x1,
                config.y1
            ) ?: return false

        val actualRgb1 =
            ScreenCaptureService.parseHexToIntOrNull(
                actualHex1
            ) ?: return false

        val match1 = ScreenCaptureService.compareRgbInt(
            actualRgb1,
            config.targetRgb1,
            config.tolerance
        )

        if (!config.useColor2) {
            return match1
        }

        if (config.colorLogic == 0 && !match1) {
            return false
        }

        if (config.colorLogic == 1 && match1) {
            return true
        }

        val actualHex2 =
            FastAccessibilityService.getStealthPixelHex(
                config.x2,
                config.y2
            ) ?: return false

        val actualRgb2 =
            ScreenCaptureService.parseHexToIntOrNull(
                actualHex2
            ) ?: return false

        val match2 = ScreenCaptureService.compareRgbInt(
            actualRgb2,
            config.targetRgb2,
            config.tolerance
        )

        return if (config.colorLogic == 0) {
            match1 && match2
        } else {
            match1 || match2
        }
    }

    private fun checkStealthArea(
        config: ColorConfig
    ): Boolean {

        val match1 =
            FastAccessibilityService.scanStealthArea(
                config.x1,
                config.y1,
                AREA_RADIUS,
                AREA_STEP,
                String.format(
                    java.util.Locale.US,
                    "#%06X",
                    config.targetRgb1
                ),
                config.tolerance
            ) != null

        if (!config.useColor2) {
            return match1
        }

        if (config.colorLogic == 0 && !match1) {
            return false
        }

        if (config.colorLogic == 1 && match1) {
            return true
        }

        val match2 =
            FastAccessibilityService.scanStealthArea(
                config.x2,
                config.y2,
                AREA_RADIUS,
                AREA_STEP,
                String.format(
                    java.util.Locale.US,
                    "#%06X",
                    config.targetRgb2
                ),
                config.tolerance
            ) != null

        return if (config.colorLogic == 0) {
            match1 && match2
        } else {
            match1 || match2
        }
    }

    /*
     * Tidur dengan penanganan interrupt yang benar.
     */
    private fun sleepSafely(
        milliseconds: Long
    ): Boolean {

        return try {

            Thread.sleep(milliseconds)
            true

        } catch (_: InterruptedException) {

            Thread.currentThread().interrupt()
            false
        }
    }
}
