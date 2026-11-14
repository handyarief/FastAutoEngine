
package com.fastauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class FastAccessibilityService : AccessibilityService() {

    companion object {

        private const val TAG = "FastAutoAccessibility"

        private const val SCREENSHOT_TIMEOUT_MS = 350L
        private const val SCREENSHOT_CACHE_MS = 250L
        private const val INVALID_RGB = -1

        @Volatile
        var instance: FastAccessibilityService? = null

        /*
         * Satu executor untuk mengatur permintaan screenshot.
         * Tidak membuat thread baru setiap pemeriksaan.
         */
        private val screenshotExecutor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(
                    runnable,
                    "FastAutoScreenshotWorker"
                ).apply {
                    isDaemon = true
                }
            }

        private val screenshotInFlight = AtomicBoolean(false)

        private val screenshotCounter = AtomicLong(0L)

        private val activeScreenshotRequest = AtomicLong(0L)

        /*
         * Cache satu screenshot sementara.
         * Berguna agar dua pemeriksaan warna berurutan
         * bisa menggunakan sumber gambar yang sama.
         */
        private val screenshotCacheLock = Any()

        private class CachedFrame(
            val bitmap: Bitmap,
            val hardwareBuffer: HardwareBuffer,
            val createdAtMs: Long
        )

        private var cachedFrame: CachedFrame? = null

        private data class CaptureOutcome<T>(
            val completed: Boolean,
            val value: T?
        )

        fun isReady(): Boolean {
            return instance != null &&
                Build.VERSION.SDK_INT >= 30
        }

        // --------------------------------------------------
        // GESTURE / TAP
        // --------------------------------------------------

        fun tap(x: Int, y: Int): Boolean {

            val service = instance ?: return false

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return false
            }

            if (x < 0 || y < 0) return false

            return try {

                val path = Path().apply {
                    moveTo(
                        x.toFloat(),
                        y.toFloat()
                    )
                }

                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0L,
                    15L
                )

                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                service.dispatchGesture(
                    gesture,
                    null,
                    null
                )

            } catch (_: Exception) {
                false
            }
        }

        // --------------------------------------------------
        // ACCESSIBILITY NODE
        // --------------------------------------------------

        fun findAndClickNode(
            targetText: String,
            clickIfFound: Boolean = true
        ): Boolean {

            val service = instance ?: return false

            val query = targetText.trim().lowercase()

            if (query.isEmpty()) return false

            val root = service.rootInActiveWindow
                ?: return false

            return try {

                val nodes =
                    root.findAccessibilityNodeInfosByText(
                        targetText.trim()
                    )

                if (!nodes.isNullOrEmpty()) {

                    for (node in nodes) {

                        if (!clickIfFound) {
                            return true
                        }

                        if (clickNodeOrParent(node)) {
                            return true
                        }
                    }
                }

                searchRecursive(
                    root,
                    query,
                    clickIfFound
                )

            } catch (_: Exception) {
                false
            }
        }

        private fun clickNodeOrParent(
            node: AccessibilityNodeInfo?
        ): Boolean {

            var current = node
            var depth = 0

            while (current != null && depth < 6) {

                if (current.isClickable) {

                    if (
                        current.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
                }

                current = current.parent
                depth++
            }

            if (node != null) {

                try {

                    val rect = android.graphics.Rect()

                    node.getBoundsInScreen(rect)

                    if (
                        rect.width() > 0 &&
                        rect.height() > 0
                    ) {
                        return tap(
                            rect.centerX(),
                            rect.centerY()
                        )
                    }

                } catch (_: Exception) {
                }
            }

            return false
        }

        private fun searchRecursive(
            node: AccessibilityNodeInfo?,
            query: String,
            clickIfFound: Boolean
        ): Boolean {

            if (node == null || query.isEmpty()) {
                return false
            }

            try {

                val text = node.text
                    ?.toString()
                    ?.lowercase() ?: ""

                val description = node.contentDescription
                    ?.toString()
                    ?.lowercase() ?: ""

                val viewId = node.viewIdResourceName
                    ?.lowercase() ?: ""

                val matches =
                    text.contains(query) ||
                    description.contains(query) ||
                    viewId.contains(query)

                if (matches) {

                    if (!clickIfFound) return true

                    if (clickNodeOrParent(node)) {
                        return true
                    }
                }

                for (i in 0 until node.childCount) {

                    val child = node.getChild(i)
                        ?: continue

                    if (
                        searchRecursive(
                            child,
                            query,
                            clickIfFound
                        )
                    ) {
                        return true
                    }
                }

            } catch (_: Exception) {
            }

            return false
        }

        // --------------------------------------------------
        // SCREENSHOT CACHE MANAGEMENT
        // --------------------------------------------------

        private fun clearCachedFrameLocked() {

            val oldFrame = cachedFrame
                ?: return

            cachedFrame = null

            try {
                oldFrame.bitmap.recycle()
            } catch (_: Exception) {
            }

            try {
                oldFrame.hardwareBuffer.close()
            } catch (_: Exception) {
            }
        }

        private fun finishScreenshotRequest(
            requestId: Long
        ) {

            if (
                activeScreenshotRequest.compareAndSet(
                    requestId,
                    0L
                )
            ) {

                screenshotInFlight.set(false)
            }
        }

        /*
         * Menghapus cache secara otomatis setelah TTL.
         */
        private fun scheduleCacheCleanup(
            frame: CachedFrame
        ) {

            try {

                screenshotExecutor.schedule(
                    {

                        synchronized(screenshotCacheLock) {

                            if (
                                cachedFrame === frame &&
                                SystemClock.elapsedRealtime() -
                                frame.createdAtMs >= SCREENSHOT_CACHE_MS
                            ) {
                                clearCachedFrameLocked()
                            }
                        }

                    },
                    SCREENSHOT_CACHE_MS,
                    TimeUnit.MILLISECONDS
                )

            } catch (_: Exception) {
                // Cache akan dibersihkan pada permintaan berikutnya.
            }
        }

        /*
         * Memotong screenshot sesuai kebutuhan,
         * lalu mengubah hanya hasil potongan menjadi software bitmap.
         */
        private fun <T> processRegionLocked(
            frame: CachedFrame,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            processor: (Bitmap, Int, Int) -> T?
        ): T? {

            val source = frame.bitmap

            if (
                width <= 0 ||
                height <= 0 ||
                source.isRecycled
            ) {
                return null
            }

            val actualLeft = left.coerceAtLeast(0)
            val actualTop = top.coerceAtLeast(0)

            val rightExclusive = (
                left.toLong() + width.toLong()
            ).coerceAtMost(
                source.width.toLong()
            )

            val bottomExclusive = (
                top.toLong() + height.toLong()
            ).coerceAtMost(
                source.height.toLong()
            )

            if (
                actualLeft.toLong() >= rightExclusive ||
                actualTop.toLong() >= bottomExclusive ||
                actualLeft >= source.width ||
                actualTop >= source.height
            ) {
                return null
            }

            val cropWidth = (
                rightExclusive - actualLeft
            ).toInt()

            val cropHeight = (
                bottomExclusive - actualTop
            ).toInt()

            var cropped: Bitmap? = null
            var software: Bitmap? = null

            try {

                cropped = Bitmap.createBitmap(
                    source,
                    actualLeft,
                    actualTop,
                    cropWidth,
                    cropHeight
                )

                software = cropped.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                ) ?: return null

                return processor(
                    software,
                    actualLeft,
                    actualTop
                )

            } catch (e: Exception) {

                android.util.Log.e(
                    TAG,
                    "Pemrosesan screenshot gagal",
                    e
                )

                return null

            } finally {

                try {
                    software?.recycle()
                } catch (_: Exception) {
                }

                try {

                    if (
                        cropped != null &&
                        cropped !== source
                    ) {
                        cropped.recycle()
                    }

                } catch (_: Exception) {
                }
            }
        }

        // --------------------------------------------------
        // SCREENSHOT REGION ENGINE
        // --------------------------------------------------

        @SuppressLint("NewApi")
        private fun <T> captureRegion(
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            processor: (Bitmap, Int, Int) -> T?
        ): T? {

            val service = instance ?: return null

            if (Build.VERSION.SDK_INT < 30) {
                return null
            }

            if (width <= 0 || height <= 0) {
                return null
            }

            /*
             * 1. Coba gunakan frame cache.
             */
            val cachedRead = synchronized(
                screenshotCacheLock
            ) {

                val frame = cachedFrame

                if (
                    frame != null &&
                    SystemClock.elapsedRealtime() -
                    frame.createdAtMs <= SCREENSHOT_CACHE_MS
                ) {

                    CaptureOutcome(
                        true,
                        processRegionLocked(
                            frame,
                            left,
                            top,
                            width,
                            height,
                            processor
                        )
                    )

                } else {

                    if (frame != null) {
                        clearCachedFrameLocked()
                    }

                    CaptureOutcome<T>(
                        false,
                        null
                    )
                }
            }

            if (cachedRead.completed) {
                return cachedRead.value
            }

            /*
             * 2. Jangan mengirim screenshot bersamaan.
             */
            if (
                !screenshotInFlight.compareAndSet(
                    false,
                    true
                )
            ) {
                return null
            }

            val requestId =
                screenshotCounter.incrementAndGet()

            activeScreenshotRequest.set(requestId)

            val latch = CountDownLatch(1)

            val outcome =
                AtomicReference<CaptureOutcome<T>?>(null)

            try {

                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {

                        override fun onSuccess(
                            screenshotResult: ScreenshotResult
                        ) {

                            var hardwareBuffer: HardwareBuffer? = null
                            var wrappedBitmap: Bitmap? = null
                            var retained = false

                            try {

                                hardwareBuffer =
                                    screenshotResult.hardwareBuffer

                                val colorSpace =
                                    screenshotResult.colorSpace
                                        ?: ColorSpace.get(
                                            ColorSpace.Named.SRGB
                                        )

                                wrappedBitmap =
                                    Bitmap.wrapHardwareBuffer(
                                        hardwareBuffer,
                                        colorSpace
                                    )

                                if (wrappedBitmap == null) {

                                    outcome.set(
                                        CaptureOutcome<T>(
                                            true,
                                            null
                                        )
                                    )

                                    return
                                }

                                val frame = CachedFrame(
                                    wrappedBitmap,
                                    hardwareBuffer,
                                    SystemClock.elapsedRealtime()
                                )

                                /*
                                 * Frame dipertahankan sebentar untuk
                                 * pemeriksaan warna berikutnya.
                                 */
                                synchronized(screenshotCacheLock) {

                                    clearCachedFrameLocked()

                                    cachedFrame = frame
                                    retained = true
                                }

                                /*
                                 * Langsung proses bagian layar
                                 * yang memang dibutuhkan.
                                 */
                                val readResult =
                                    synchronized(screenshotCacheLock) {

                                        if (cachedFrame === frame) {

                                            processRegionLocked(
                                                frame,
                                                left,
                                                top,
                                                width,
                                                height,
                                                processor
                                            )

                                        } else {
                                            null
                                        }
                                    }

                                outcome.set(
                                    CaptureOutcome(
                                        true,
                                        readResult
                                    )
                                )

                                scheduleCacheCleanup(frame)

                            } catch (e: Exception) {

                                android.util.Log.e(
                                    TAG,
                                    "Screenshot callback error",
                                    e
                                )

                                outcome.set(
                                    CaptureOutcome<T>(
                                        true,
                                        null
                                    )
                                )

                            } finally {

                                if (!retained) {

                                    try {
                                        wrappedBitmap?.recycle()
                                    } catch (_: Exception) {
                                    }

                                    try {
                                        hardwareBuffer?.close()
                                    } catch (_: Exception) {
                                    }
                                }

                                finishScreenshotRequest(
                                    requestId
                                )

                                latch.countDown()
                            }
                        }

                        override fun onFailure(
                            errorCode: Int
                        ) {

                            android.util.Log.w(
                                TAG,
                                "Screenshot gagal: $errorCode"
                            )

                            finishScreenshotRequest(
                                requestId
                            )

                            latch.countDown()
                        }
                    }
                )

            } catch (e: Exception) {

                android.util.Log.e(
                    TAG,
                    "Permintaan screenshot gagal",
                    e
                )

                finishScreenshotRequest(requestId)

                return null
            }

            val completed = try {

                latch.await(
                    SCREENSHOT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )

            } catch (_: InterruptedException) {

                Thread.currentThread().interrupt()

                finishScreenshotRequest(requestId)

                false
            }

            if (!completed) {

                /*
                 * Request yang terlambat tidak boleh
                 * menghalangi request baru selamanya.
                 */
                finishScreenshotRequest(requestId)

                return null
            }

            return outcome.get()?.value
        }

        // --------------------------------------------------
        // FAST PIXEL READING
        // --------------------------------------------------

        /*
         * Membaca beberapa koordinat dari SATU screenshot.
         *
         * Format:
         * intArrayOf(x1, y1, x2, y2, ...)
         *
         * INVALID_RGB (-1) berarti koordinat tidak valid.
         */
        fun getStealthPixelsRgb(
            coordinates: IntArray
        ): IntArray? {

            if (
                Build.VERSION.SDK_INT < 30 ||
                coordinates.isEmpty() ||
                coordinates.size % 2 != 0
            ) {
                return null
            }

            val pointCount = coordinates.size / 2

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            for (i in 0 until pointCount) {

                val x = coordinates[i * 2]
                val y = coordinates[i * 2 + 1]

                minX = minOf(minX, x)
                minY = minOf(minY, y)

                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }

            val widthLong =
                maxX.toLong() - minX.toLong() + 1L

            val heightLong =
                maxY.toLong() - minY.toLong() + 1L

            if (
                widthLong <= 0L ||
                heightLong <= 0L ||
                widthLong > Int.MAX_VALUE ||
                heightLong > Int.MAX_VALUE
            ) {
                return null
            }

            return captureRegion(
                minX,
                minY,
                widthLong.toInt(),
                heightLong.toInt()
            ) { bitmap, originX, originY ->

                val values = IntArray(pointCount) {
                    INVALID_RGB
                }

                for (i in 0 until pointCount) {

                    val x = coordinates[i * 2]
                    val y = coordinates[i * 2 + 1]

                    val localX = x - originX
                    val localY = y - originY

                    if (
                        localX !in 0 until bitmap.width ||
                        localY !in 0 until bitmap.height
                    ) {
                        continue
                    }

                    val pixel = bitmap.getPixel(
                        localX,
                        localY
                    )

                    values[i] = pixel and 0xFFFFFF
                }

                values
            }
        }

        /*
         * Kompatibilitas dengan PluginFireReceiver.
         */
        fun getStealthPixelHex(
            x: Int,
            y: Int
        ): String? {

            val rgb = getStealthPixelsRgb(
                intArrayOf(x, y)
            )?.firstOrNull() ?: return null

            if (rgb == INVALID_RGB) {
                return null
            }

            return String.format(
                java.util.Locale.US,
                "#%06X",
                rgb and 0xFFFFFF
            )
        }

        // --------------------------------------------------
        // PRECISION AREA SCAN
        // --------------------------------------------------

        @SuppressLint("NewApi")
        fun scanStealthArea(
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetHex: String,
            tolerance: Int
        ): Pair<Int, Int>? {

            if (Build.VERSION.SDK_INT < 30) {
                return null
            }

            if (
                radius !in 0..512 ||
                tolerance !in 0..255
            ) {
                return null
            }

            val targetRgb =
                ScreenCaptureService.parseHexToIntOrNull(
                    targetHex
                ) ?: return null

            val safeStep = step.coerceAtLeast(1)

            val left = centerX.toLong() - radius
            val top = centerY.toLong() - radius

            val width = radius.toLong() * 2L + 1L
            val height = radius.toLong() * 2L + 1L

            if (
                left < Int.MIN_VALUE ||
                left > Int.MAX_VALUE ||
                top < Int.MIN_VALUE ||
                top > Int.MAX_VALUE
            ) {
                return null
            }

            return captureRegion(
                left.toInt(),
                top.toInt(),
                width.toInt(),
                height.toInt()
            ) { bitmap, originX, originY ->

                /*
                 * Membaca seluruh area yang diperlukan
                 * sekaligus ke array piksel lokal.
                 */
                val pixels = IntArray(
                    bitmap.width * bitmap.height
                )

                bitmap.getPixels(
                    pixels,
                    0,
                    bitmap.width,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height
                )

                for (y in 0 until bitmap.height step safeStep) {

                    val rowOffset = y * bitmap.width

                    for (x in 0 until bitmap.width step safeStep) {

                        val rgb =
                            pixels[rowOffset + x] and 0xFFFFFF

                        if (
                            ScreenCaptureService.compareRgbInt(
                                rgb,
                                targetRgb,
                                tolerance
                            )
                        ) {

                            return@captureRegion Pair(
                                originX + x,
                                originY + y
                            )
                        }
                    }
                }

                null
            }
        }
    }

    // --------------------------------------------------
    // SERVICE LIFECYCLE
    // --------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        android.util.Log.i(
            TAG,
            "FastAccessibilityService aktif"
        )
    }

    /*
     * Tidak melakukan tindakan otomatis terhadap peringatan
     * keamanan atau dialog aplikasi lain.
     *
     * Event Accessibility tetap tersedia untuk penggunaan
     * fungsi interaksi yang memang diminta pengguna.
     */
    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // Tidak ada auto-dismiss atau auto-click.
    }

    override fun onInterrupt() {
        // Tidak ada tindakan khusus.
    }

    override fun onDestroy() {

        if (instance === this) {
            instance = null
        }

        synchronized(screenshotCacheLock) {
            clearCachedFrameLocked()
        }

        super.onDestroy()
    }
}
