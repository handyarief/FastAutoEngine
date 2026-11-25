package com.fastauto.engine

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenCaptureService : Service() {

    enum class EvalResult {
        MATCHED,
        NOT_MATCHED,
        ENGINE_NOT_READY,
        CAPTURE_FAILED
    }

    companion object {
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        const val LOGIC_AND = 0
        const val LOGIC_OR = 1

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fastauto_capture_channel"
        private const val INITIAL_FRAME_WAIT_MS = 400L
        private const val MAX_WAIT_TIMEOUT_MS = 5000L

        @Volatile
        var instance: ScreenCaptureService? = null

        @Volatile var screenWidth: Int = 0
        @Volatile var screenHeight: Int = 0
        @Volatile var screenDensity: Int = 0

        private val captureLock = Object()
        private val frameSignal = Object()

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var pickerCacheImage: Image? = null

        private var bgThread: HandlerThread? = null
        private var bgHandler: Handler? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        private var activeSessions: Int = 0
        private var pickerRequested: Boolean = false
        private var pickerSessionHeld: Boolean = false
        private var hasCapturedContentResize: Boolean = false
        private var frameSeq: Long = 0L

        private val onDemandFrameListener = ImageReader.OnImageAvailableListener {
            synchronized(frameSignal) {
                frameSeq++
                frameSignal.notifyAll()
            }
        }

        fun isReady(): Boolean {
            synchronized(captureLock) {
                return mediaProjection != null && virtualDisplay != null
            }
        }

        /**
         * Menjaga sesi pembacaan tetap hangat HANYA selama overlay Alat Bidik terbuka
         * agar proses geser bidik mulus. Saat Alat Bidik ditutup (false), Surface langsung
         * dilepas dan ImageReader ditutup kembali ke Strict True-Idle (0 MB Graphics Buffer).
         */
        fun setPickerActive(active: Boolean) {
            synchronized(captureLock) {
                pickerRequested = active
                if (active) {
                    if (!pickerSessionHeld && mediaProjection != null && virtualDisplay != null) {
                        if (ensureReaderAttachedLocked(maxImages = 3) != null) {
                            activeSessions++
                            pickerSessionHeld = true
                        }
                    }
                } else {
                    if (pickerSessionHeld) {
                        pickerSessionHeld = false
                        closePickerCacheLocked()
                        releaseSessionLocked()
                    }
                }
            }
        }

        /**
         * Evaluasi warna On-Demand khusus area ROI (1 warna atau 2 warna AND/OR).
         * Pola: Strict True-Idle -> Pasang Surface -> 1 Frame Segar -> Pindai ROI -> Tutup & Idle.
         */
        fun evaluateAreaOnDemand(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int,
            timeoutMs: Long
        ): EvalResult {
            val rad = radius.coerceIn(1, 500)
            val stp = step.coerceIn(1, 20)
            val tol = tolerance.coerceIn(0, 255)
            val safeTimeout = timeoutMs.coerceIn(0L, MAX_WAIT_TIMEOUT_MS)

            var sessionAcquired = false
            var seqBeforeAttach = 0L
            var startedFromIdle = false

            synchronized(captureLock) {
                if (mediaProjection == null || virtualDisplay == null) {
                    return EvalResult.ENGINE_NOT_READY
                }
                startedFromIdle = (imageReader == null)
                seqBeforeAttach = synchronized(frameSignal) { frameSeq }
                if (ensureReaderAttachedLocked(maxImages = if (pickerSessionHeld) 3 else 2) == null) {
                    return EvalResult.ENGINE_NOT_READY
                }
                activeSessions++
                sessionAcquired = true
            }

            try {
                val now = System.nanoTime()
                val waitWindowMs = if (safeTimeout > 0L) safeTimeout else INITIAL_FRAME_WAIT_MS
                val deadlineNanos = now + (waitWindowMs * 1_000_000L)

                var lastSeenSeq = seqBeforeAttach
                var evaluatedAtLeastOneFrame = false

                while (true) {
                    val scanOutcome = acquireAndEvaluateOnce(
                        x1, y1, rgb1,
                        useColor2, colorLogic,
                        x2, y2, rgb2,
                        rad, stp, tol,
                        allowPickerCacheFallback = !startedFromIdle && pickerSessionHeld && !evaluatedAtLeastOneFrame
                    )

                    when (scanOutcome) {
                        ScanStep.MATCHED -> return EvalResult.MATCHED
                        ScanStep.ENGINE_STOPPED -> return EvalResult.ENGINE_NOT_READY
                        ScanStep.NOT_MATCHED -> {
                            evaluatedAtLeastOneFrame = true
                            if (safeTimeout <= 0L) {
                                return EvalResult.NOT_MATCHED
                            }
                        }
                        ScanStep.NO_IMAGE_YET -> {
                            // Tunggu frame segar dari SurfaceFlinger hingga deadline
                        }
                    }

                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        return if (evaluatedAtLeastOneFrame) {
                            EvalResult.NOT_MATCHED
                        } else {
                            EvalResult.CAPTURE_FAILED
                        }
                    }

                    val waitMs = ((remainingNanos + 999_999L) / 1_000_000L).coerceIn(1L, 50L)
                    synchronized(frameSignal) {
                        if (frameSeq == lastSeenSeq) {
                            try {
                                frameSignal.wait(waitMs)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return if (evaluatedAtLeastOneFrame) {
                                    EvalResult.NOT_MATCHED
                                } else {
                                    EvalResult.CAPTURE_FAILED
                                }
                            }
                        }
                        lastSeenSeq = frameSeq
                    }
                }
            } finally {
                if (sessionAcquired) {
                    synchronized(captureLock) {
                        releaseSessionLocked()
                    }
                }
            }
        }

        /**
         * Membaca 1 piksel warna untuk Alat Bidik di MainActivity.
         * Harus dipanggil dari background thread.
         */
        fun getPixelHex(x: Int, y: Int): String? {
            var tempSession = false
            var seqBefore = 0L

            synchronized(captureLock) {
                if (mediaProjection == null || virtualDisplay == null) return null
                seqBefore = synchronized(frameSignal) { frameSeq }
                if (imageReader == null) {
                    if (ensureReaderAttachedLocked(maxImages = if (pickerSessionHeld) 3 else 2) == null) {
                        return null
                    }
                    activeSessions++
                    tempSession = true
                }
            }

            try {
                val deadlineNanos = System.nanoTime() + (INITIAL_FRAME_WAIT_MS * 1_000_000L)
                var lastSeq = seqBefore

                while (true) {
                    val hex = readSinglePixelLocked(x, y)
                    if (hex != null) return hex

                    val remNanos = deadlineNanos - System.nanoTime()
                    if (remNanos <= 0L) return null

                    val waitMs = ((remNanos + 999_999L) / 1_000_000L).coerceIn(1L, 35L)
                    synchronized(frameSignal) {
                        if (frameSeq == lastSeq) {
                            try {
                                frameSignal.wait(waitMs)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return null
                            }
                        }
                        lastSeq = frameSeq
                    }
                }
            } finally {
                if (tempSession) {
                    synchronized(captureLock) {
                        releaseSessionLocked()
                    }
                }
            }
        }

        /**
         * Validasi ketat string warna Hex (#RRGGBB atau RRGGBB).
         * Mengembalikan null jika format salah (tidak jatuh diam-diam ke merah).
         */
        fun parseHexToIntOrNull(hex: String?): Int? {
            if (hex == null) return null
            val trimmed = hex.trim()
            val clean = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
            if (clean.length != 6) return null
            return try {
                clean.toInt(16) and 0xFFFFFF
            } catch (_: NumberFormatException) {
                null
            }
        }

        private enum class ScanStep {
            MATCHED,
            NOT_MATCHED,
            NO_IMAGE_YET,
            ENGINE_STOPPED
        }

        private fun acquireAndEvaluateOnce(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int,
            allowPickerCacheFallback: Boolean
        ): ScanStep {
            synchronized(captureLock) {
                val reader = imageReader ?: return ScanStep.ENGINE_STOPPED
                val freshImg = try {
                    reader.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                }

                if (freshImg != null) {
                    return try {
                        val matched = evaluateImageRoi(
                            freshImg,
                            x1, y1, rgb1,
                            useColor2, colorLogic,
                            x2, y2, rgb2,
                            radius, step, tolerance
                        )
                        if (matched) ScanStep.MATCHED else ScanStep.NOT_MATCHED
                    } finally {
                        try { freshImg.close() } catch (_: Throwable) {}
                    }
                }

                if (allowPickerCacheFallback) {
                    val cached = pickerCacheImage
                    if (cached != null) {
                        val matched = evaluateImageRoi(
                            cached,
                            x1, y1, rgb1,
                            useColor2, colorLogic,
                            x2, y2, rgb2,
                            radius, step, tolerance
                        )
                        return if (matched) ScanStep.MATCHED else ScanStep.NOT_MATCHED
                    }
                }

                return ScanStep.NO_IMAGE_YET
            }
        }

        private fun readSinglePixelLocked(x: Int, y: Int): String? {
            synchronized(captureLock) {
                val reader = imageReader ?: return null
                val freshImg = try {
                    reader.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                }

                val targetImg: Image?
                var shouldCloseAfterRead = false

                if (freshImg != null) {
                    if (pickerSessionHeld) {
                        closePickerCacheLocked()
                        pickerCacheImage = freshImg
                        targetImg = freshImg
                    } else {
                        targetImg = freshImg
                        shouldCloseAfterRead = true
                    }
                } else {
                    targetImg = if (pickerSessionHeld) pickerCacheImage else null
                }

                if (targetImg == null) return null

                return try {
                    val planes = targetImg.planes
                    if (planes.isNullOrEmpty()) return null
                    val plane = planes[0]
                    val buf = plane.buffer ?: return null
                    val w = targetImg.width
                    val h = targetImg.height
                    val rs = plane.rowStride
                    val ps = plane.pixelStride
                    if (w <= 0 || h <= 0 || rs <= 0 || ps <= 0) return null

                    val px = x.coerceIn(0, w - 1)
                    val py = y.coerceIn(0, h - 1)
                    val off = py * rs + px * ps
                    val limit = buf.limit()
                    if (off < 0 || off + 2 >= limit) return null

                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val r: Int
                    val g: Int
                    val b: Int
                    if (off + 3 < limit) {
                        val pixel = buf.getInt(off)
                        r = pixel and 0xFF
                        g = (pixel ushr 8) and 0xFF
                        b = (pixel ushr 16) and 0xFF
                    } else {
                        r = buf.get(off).toInt() and 0xFF
                        g = buf.get(off + 1).toInt() and 0xFF
                        b = buf.get(off + 2).toInt() and 0xFF
                    }
                    val rgb = (r shl 16) or (g shl 8) or b
                    String.format("#%06X", rgb)
                } catch (_: Throwable) {
                    null
                } finally {
                    if (shouldCloseAfterRead) {
                        try { targetImg.close() } catch (_: Throwable) {}
                    }
                }
            }
        }

        private fun evaluateImageRoi(
            img: Image,
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int
        ): Boolean {
            val planes = try { img.planes } catch (_: Throwable) { null } ?: return false
            if (planes.isEmpty()) return false
            val plane = planes[0]
            val buf = plane.buffer ?: return false

            val w = img.width
            val h = img.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            if (w <= 0 || h <= 0 || rs <= 0 || ps <= 0) return false

            buf.order(ByteOrder.LITTLE_ENDIAN)

            if (!useColor2) {
                return scanAreaCenterOutward(buf, w, h, rs, ps, x1, y1, radius, step, rgb1, tolerance)
            }

            // Optimasi Single-Pass jika Area 1 dan Area 2 berada di koordinat pusat yang sama
            if (x1 == x2 && y1 == y2) {
                return scanAreaDualSameCenter(
                    buf, w, h, rs, ps,
                    x1, y1, radius, step,
                    rgb1, rgb2, colorLogic, tolerance
                )
            }

            val m1 = scanAreaCenterOutward(buf, w, h, rs, ps, x1, y1, radius, step, rgb1, tolerance)
            return if (colorLogic == LOGIC_AND) {
                if (!m1) false else scanAreaCenterOutward(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
            } else {
                if (m1) true else scanAreaCenterOutward(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
            }
        }

        /**
         * Memindai HANYA kotak area (centerX ± radius, centerY ± radius) dari titik pusat
         * melebar keluar (expanding square rings) menggunakan Little-Endian native.
         */
        private fun scanAreaCenterOutward(
            buf: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetRgb: Int,
            tolerance: Int
        ): Boolean {
            val r2 = (targetRgb ushr 16) and 0xFF
            val g2 = (targetRgb ushr 8) and 0xFF
            val b2 = targetRgb and 0xFF

            val rMin = r2 - tolerance
            val rMax = r2 + tolerance
            val gMin = g2 - tolerance
            val gMax = g2 + tolerance
            val bMin = b2 - tolerance
            val bMax = b2 + tolerance

            val cx = centerX.coerceIn(0, width - 1)
            val cy = centerY.coerceIn(0, height - 1)
            val limit = buf.limit()
            val maxX = width - 1
            val maxY = height - 1

            // 1. Cek titik pusat (1 piksel) terlebih dahulu
            if (matchPixelAt(buf, limit, cy * rowStride + cx * pixelStride, rMin, rMax, gMin, gMax, bMin, bMax)) {
                return true
            }

            // 2. Pindai cincin persegi melebar keluar hingga batas radius
            var d = step
            while (d <= radius) {
                val left = (cx - d).coerceAtLeast(0)
                val right = (cx + d).coerceAtMost(maxX)
                val top = cy - d
                val bottom = cy + d

                // Sisi atas cincin
                if (top >= 0) {
                    val rowBase = top * rowStride
                    var x = left
                    while (x <= right) {
                        if (matchPixelAt(buf, limit, rowBase + x * pixelStride, rMin, rMax, gMin, gMax, bMin, bMax)) {
                            return true
                        }
                        x += step
                    }
                }

                // Sisi bawah cincin
                if (bottom <= maxY) {
                    val rowBase = bottom * rowStride
                    var x = left
                    while (x <= right) {
                        if (matchPixelAt(buf, limit, rowBase + x * pixelStride, rMin, rMax, gMin, gMax, bMin, bMax)) {
                            return true
                        }
                        x += step
                    }
                }

                val innerTop = (top + step).coerceAtLeast(0)
                val innerBottom = (bottom - step).coerceAtMost(maxY)

                // Sisi kiri cincin (tanpa sudut yang sudah dicek)
                val leftX = cx - d
                if (leftX >= 0 && innerTop <= innerBottom) {
                    val colOff = leftX * pixelStride
                    var y = innerTop
                    while (y <= innerBottom) {
                        if (matchPixelAt(buf, limit, y * rowStride + colOff, rMin, rMax, gMin, gMax, bMin, bMax)) {
                            return true
                        }
                        y += step
                    }
                }

                // Sisi kanan cincin (tanpa sudut yang sudah dicek)
                val rightX = cx + d
                if (rightX <= maxX && innerTop <= innerBottom) {
                    val colOff = rightX * pixelStride
                    var y = innerTop
                    while (y <= innerBottom) {
                        if (matchPixelAt(buf, limit, y * rowStride + colOff, rMin, rMax, gMin, gMax, bMin, bMax)) {
                            return true
                        }
                        y += step
                    }
                }

                d += step
            }

            return false
        }

        /**
         * Pemindaian Single-Pass untuk 2 warna pada pusat area yang sama.
         */
        private fun scanAreaDualSameCenter(
            buf: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            rgb1: Int,
            rgb2: Int,
            colorLogic: Int,
            tolerance: Int
        ): Boolean {
            val r1 = (rgb1 ushr 16) and 0xFF
            val g1 = (rgb1 ushr 8) and 0xFF
            val b1 = rgb1 and 0xFF
            val r1Min = r1 - tolerance; val r1Max = r1 + tolerance
            val g1Min = g1 - tolerance; val g1Max = g1 + tolerance
            val b1Min = b1 - tolerance; val b1Max = b1 + tolerance

            val r2 = (rgb2 ushr 16) and 0xFF
            val g2 = (rgb2 ushr 8) and 0xFF
            val b2 = rgb2 and 0xFF
            val r2Min = r2 - tolerance; val r2Max = r2 + tolerance
            val g2Min = g2 - tolerance; val g2Max = g2 + tolerance
            val b2Min = b2 - tolerance; val b2Max = b2 + tolerance

            val isOr = (colorLogic == LOGIC_OR)
            val cx = centerX.coerceIn(0, width - 1)
            val cy = centerY.coerceIn(0, height - 1)
            val limit = buf.limit()
            val maxX = width - 1
            val maxY = height - 1

            var found1 = false
            var found2 = false

            fun checkOffset(off: Int): Boolean {
                if (off < 0 || off + 2 >= limit) return false
                val r: Int
                val g: Int
                val b: Int
                if (off + 3 < limit) {
                    val pixel = buf.getInt(off)
                    r = pixel and 0xFF
                    g = (pixel ushr 8) and 0xFF
                    b = (pixel ushr 16) and 0xFF
                } else {
                    r = buf.get(off).toInt() and 0xFF
                    g = buf.get(off + 1).toInt() and 0xFF
                    b = buf.get(off + 2).toInt() and 0xFF
                }
                if (!found1 && r in r1Min..r1Max && g in g1Min..g1Max && b in b1Min..b1Max) {
                    found1 = true
                    if (isOr || found2) return true
                }
                if (!found2 && r in r2Min..r2Max && g in g2Min..g2Max && b in b2Min..b2Max) {
                    found2 = true
                    if (isOr || found1) return true
                }
                return false
            }

            if (checkOffset(cy * rowStride + cx * pixelStride)) return true

            var d = step
            while (d <= radius) {
                val left = (cx - d).coerceAtLeast(0)
                val right = (cx + d).coerceAtMost(maxX)
                val top = cy - d
                val bottom = cy + d

                if (top >= 0) {
                    val rowBase = top * rowStride
                    var x = left
                    while (x <= right) {
                        if (checkOffset(rowBase + x * pixelStride)) return true
                        x += step
                    }
                }
                if (bottom <= maxY) {
                    val rowBase = bottom * rowStride
                    var x = left
                    while (x <= right) {
                        if (checkOffset(rowBase + x * pixelStride)) return true
                        x += step
                    }
                }

                val innerTop = (top + step).coerceAtLeast(0)
                val innerBottom = (bottom - step).coerceAtMost(maxY)

                val leftX = cx - d
                if (leftX >= 0 && innerTop <= innerBottom) {
                    val colOff = leftX * pixelStride
                    var y = innerTop
                    while (y <= innerBottom) {
                        if (checkOffset(y * rowStride + colOff)) return true
                        y += step
                    }
                }
                val rightX = cx + d
                if (rightX <= maxX && innerTop <= innerBottom) {
                    val colOff = rightX * pixelStride
                    var y = innerTop
                    while (y <= innerBottom) {
                        if (checkOffset(y * rowStride + colOff)) return true
                        y += step
                    }
                }
                d += step
            }
            return false
        }

        private fun matchPixelAt(
            buf: ByteBuffer,
            limit: Int,
            off: Int,
            rMin: Int, rMax: Int,
            gMin: Int, gMax: Int,
            bMin: Int, bMax: Int
        ): Boolean {
            if (off < 0 || off + 2 >= limit) return false
            val r: Int
            val g: Int
            val b: Int
            if (off + 3 < limit) {
                val pixel = buf.getInt(off)
                r = pixel and 0xFF
                g = (pixel ushr 8) and 0xFF
                b = (pixel ushr 16) and 0xFF
            } else {
                r = buf.get(off).toInt() and 0xFF
                g = buf.get(off + 1).toInt() and 0xFF
                b = buf.get(off + 2).toInt() and 0xFF
            }
            return r in rMin..rMax && g in gMin..gMax && b in bMin..bMax
        }

        private fun ensureReaderAttachedLocked(maxImages: Int): ImageReader? {
            val vd = virtualDisplay ?: return null
            if (mediaProjection == null) return null
            val w = screenWidth
            val h = screenHeight
            if (w <= 0 || h <= 0) return null

            var reader = imageReader
            if (reader == null) {
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, maxImages)
                reader.setOnImageAvailableListener(onDemandFrameListener, bgHandler)
                imageReader = reader
                vd.setSurface(reader.surface)
            }
            return reader
        }

        private fun releaseSessionLocked() {
            if (activeSessions > 0) {
                activeSessions--
            }
            if (activeSessions <= 0) {
                activeSessions = 0
                detachAndCloseReaderLocked()
            }
        }

        private fun closePickerCacheLocked() {
            val cached = pickerCacheImage
            pickerCacheImage = null
            if (cached != null) {
                try { cached.close() } catch (_: Throwable) {}
            }
        }

        private fun detachAndCloseReaderLocked() {
            closePickerCacheLocked()
            try { virtualDisplay?.setSurface(null) } catch (_: Throwable) {}
            val reader = imageReader
            imageReader = null
            if (reader != null) {
                try { reader.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
                try { reader.close() } catch (_: Throwable) {}
            }
        }
    }

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                syncDisplayMetricsFromSystem()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            shutdownServiceCompletely()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                startForegroundServiceNotification()
                val ok = setupProjection(resultCode, data)
                if (!ok) {
                    shutdownServiceCompletely()
                }
            } catch (_: Throwable) {
                shutdownServiceCompletely()
            }
        } else if (!isReady()) {
            stopSelf()
        }

        // START_NOT_STICKY wajib untuk MediaProjection agar tidak crash saat proses dihidupkan ulang sistem
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncDisplayMetricsFromSystem()
    }

    private fun getExactPhysicalSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.configuration.densityDpi.coerceAtLeast(120)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
                metrics.densityDpi.coerceAtLeast(120)
            )
        }
    }

    private fun syncDisplayMetricsFromSystem() {
        synchronized(captureLock) {
            if (hasCapturedContentResize) return
            if (virtualDisplay == null) return
        }
        val (w, h, dpi) = getExactPhysicalSize()
        applyResize(w, h, dpi)
    }

    private fun applyResize(newW: Int, newH: Int, newDpi: Int) {
        if (newW <= 0 || newH <= 0) return
        synchronized(captureLock) {
            val vd = virtualDisplay ?: return
            if (newW == screenWidth && newH == screenHeight && newDpi == screenDensity) return

            screenWidth = newW
            screenHeight = newH
            screenDensity = newDpi

            val wasAttached = (imageReader != null)
            if (wasAttached) {
                detachAndCloseReaderLocked()
            }
            try {
                vd.resize(newW, newH, newDpi)
            } catch (_: Throwable) {}

            if (wasAttached && activeSessions > 0) {
                ensureReaderAttachedLocked(maxImages = if (pickerSessionHeld) 3 else 2)
            }
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent): Boolean {
        stopProjection()

        val (w, h, dpi) = getExactPhysicalSize()
        val thread = HandlerThread("FastColorFrameThread", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val handler = Handler(thread.looper)

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, data) ?: run {
            try { thread.quitSafely() } catch (_: Throwable) {}
            return false
        }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdownServiceCompletely()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                synchronized(captureLock) {
                    hasCapturedContentResize = true
                }
                val currentDpi = resources.configuration.densityDpi.coerceAtLeast(120)
                applyResize(width, height, currentDpi)
            }
        }, mainHandler)

        // Buat VirtualDisplay dalam kondisi PAUSED (surface = null) agar langsung Strict True-Idle (0% GPU/CPU, 0 MB Buffer)
        val vd = projection.createVirtualDisplay(
            "FastAutoColorEngine",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            handler
        ) ?: run {
            try { projection.stop() } catch (_: Throwable) {}
            try { thread.quitSafely() } catch (_: Throwable) {}
            return false
        }

        synchronized(captureLock) {
            screenWidth = w
            screenHeight = h
            screenDensity = dpi
            hasCapturedContentResize = false
            bgThread = thread
            bgHandler = handler
            mediaProjection = projection
            virtualDisplay = vd

            // Jika pengguna membuka Alat Bidik sebelum menyalakan Mesin RAM, langsung aktifkan sesi picker
            if (pickerRequested && !pickerSessionHeld) {
                if (ensureReaderAttachedLocked(maxImages = 3) != null) {
                    activeSessions++
                    pickerSessionHeld = true
                }
            }
        }
        return true
    }

    private fun shutdownServiceCompletely() {
        stopProjection()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}
        stopSelf()
    }

    private fun stopProjection() {
        val threadToQuit: HandlerThread?
        val vdToRelease: VirtualDisplay?
        val mpToStop: MediaProjection?

        synchronized(captureLock) {
            activeSessions = 0
            pickerSessionHeld = false
            hasCapturedContentResize = false
            detachAndCloseReaderLocked()

            vdToRelease = virtualDisplay
            virtualDisplay = null
            mpToStop = mediaProjection
            mediaProjection = null
            threadToQuit = bgThread
            bgThread = null
            bgHandler = null
        }

        synchronized(frameSignal) {
            frameSignal.notifyAll()
        }

        try { vdToRelease?.release() } catch (_: Throwable) {}
        try { mpToStop?.stop() } catch (_: Throwable) {}
        try { threadToQuit?.quitSafely() } catch (_: Throwable) {}
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FastAuto Area Engine",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FastAuto Area Engine")
            .setContentText("On-Demand Area Color Scanner (True-Idle)")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        stopProjection()
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
