package com.fastauto.engine

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        @Volatile
        var instance: ScreenCaptureService? = null

        private var mediaProjection: MediaProjection? = null
        private var imageReader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var bgThread: HandlerThread? = null
        private var bgHandler: Handler? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile var screenWidth = 0
        @Volatile var screenHeight = 0
        @Volatile var screenDensity = 0

        @Volatile var isPickerOverlayOpen: Boolean = false

        private val frameLock = Object()

        // Menyimpan referensi Image terakhir langsung di memori Native GPU (Zero-Copy 0 MB Heap!)
        private var latestImage: android.media.Image? = null
        @Volatile private var cachedW = 0
        @Volatile private var cachedH = 0
        @Volatile private var cachedRowStride = 0
        @Volatile private var cachedPixelStride = 4

        fun isReady(): Boolean = imageReader != null && mediaProjection != null

        fun updatePickerTarget(x: Int, y: Int) {
            // Sistem Zero-Copy Direct Buffer 0% CPU saat idle
        }

        fun registerWatchConfig(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int
        ): Boolean {
            return true
        }

        /**
         * Menukar (swap) referensi frame terbaru dari ImageReader secara instan tanpa menyalin 10MB byte!
         * Waktu eksekusi: ~0.001 ms.
         */
        private fun acquireFreshImageIfAny() {
            val reader = imageReader ?: return
            val newImg = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return

            synchronized(frameLock) {
                try {
                    val planes = newImg.planes
                    if (!planes.isNullOrEmpty() && planes[0].buffer != null) {
                        latestImage?.close()
                        latestImage = newImg
                        cachedW = newImg.width
                        cachedH = newImg.height
                        cachedRowStride = planes[0].rowStride
                        cachedPixelStride = planes[0].pixelStride
                        frameLock.notifyAll()
                        return
                    }
                } catch (_: Throwable) {
                }
                try {
                    newImg.close()
                } catch (_: Throwable) {}
            }
        }

        fun evaluateAreaInstantOrWait(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int,
            timeoutMs: Long
        ): Boolean {
            val rad = radius.coerceIn(1, 500)
            val stp = step.coerceIn(1, 20)
            val tol = tolerance.coerceIn(0, 255)

            // 1. Ambil pointer frame terbaru dari GPU (Zero-Copy)
            acquireFreshImageIfAny()

            // 2. Evaluasi langsung dari DirectByteBuffer (< 0.01ms)
            if (checkCurrentBuffer(x1, y1, rgb1, useColor2, colorLogic, x2, y2, rgb2, rad, stp, tol)) {
                return true
            }

            if (timeoutMs <= 0L) {
                return false
            }

            // 3. Jika mode tunggu (timeout > 0), tunggu sinyal frame baru dengan 0% CPU
            val deadline = System.nanoTime() + (timeoutMs.coerceAtMost(8000L) * 1_000_000L)
            while (System.nanoTime() < deadline) {
                acquireFreshImageIfAny()
                if (checkCurrentBuffer(x1, y1, rgb1, useColor2, colorLogic, x2, y2, rgb2, rad, stp, tol)) {
                    return true
                }
                synchronized(frameLock) {
                    val remMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1L, 16L)
                    try {
                        frameLock.wait(remMs)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
            return false
        }

        private fun checkCurrentBuffer(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int
        ): Boolean {
            synchronized(frameLock) {
                val img = latestImage ?: return false
                val planes = try { img.planes } catch (_: Throwable) { null } ?: return false
                if (planes.isEmpty()) return false
                val buf = planes[0].buffer ?: return false

                val w = cachedW
                val h = cachedH
                val rs = cachedRowStride
                val ps = cachedPixelStride
                if (w <= 0 || h <= 0 || rs <= 0) return false

                val m1 = scanDirectBufferArea(buf, w, h, rs, ps, x1, y1, radius, step, rgb1, tolerance)
                return if (!useColor2) {
                    m1
                } else if (colorLogic == 0) {
                    if (!m1) false else scanDirectBufferArea(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
                } else {
                    if (m1) true else scanDirectBufferArea(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
                }
            }
        }

        /**
         * Membaca piksel langsung dari memori Native (DirectByteBuffer) dengan indeks absolut.
         * Tidak ada alokasi ByteArray sama sekali!
         */
        private fun scanDirectBufferArea(
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
            val r2 = (targetRgb shr 16) and 0xFF
            val g2 = (targetRgb shr 8) and 0xFF
            val b2 = targetRgb and 0xFF

            val cx = centerX.coerceIn(0, width - 1)
            val cy = centerY.coerceIn(0, height - 1)
            val limit = buf.limit()

            // 1. Cek titik pusat terlebih dahulu (0.0001 ms)
            val centerOff = cy * rowStride + cx * pixelStride
            if (centerOff >= 0 && centerOff + 2 < limit) {
                val cr = buf.get(centerOff).toInt() and 0xFF
                val cg = buf.get(centerOff + 1).toInt() and 0xFF
                val cb = buf.get(centerOff + 2).toInt() and 0xFF
                if (abs(cr - r2) <= tolerance && abs(cg - g2) <= tolerance && abs(cb - b2) <= tolerance) {
                    return true
                }
            }

            // 2. Pindai area kotak sekitar titik pusat
            val startX = (cx - radius).coerceAtLeast(0)
            val endX = (cx + radius).coerceAtMost(width - 1)
            val startY = (cy - radius).coerceAtLeast(0)
            val endY = (cy + radius).coerceAtMost(height - 1)
            val strideStep = step * pixelStride

            for (y in startY..endY step step) {
                var off = y * rowStride + startX * pixelStride
                val maxOff = y * rowStride + endX * pixelStride
                if (off < 0 || maxOff + 2 >= limit) continue

                while (off <= maxOff) {
                    val r1 = buf.get(off).toInt() and 0xFF
                    val g1 = buf.get(off + 1).toInt() and 0xFF
                    val b1 = buf.get(off + 2).toInt() and 0xFF
                    if (abs(r1 - r2) <= tolerance &&
                        abs(g1 - g2) <= tolerance &&
                        abs(b1 - b2) <= tolerance
                    ) {
                        return true
                    }
                    off += strideStep
                }
            }
            return false
        }

        fun getPixelHex(x: Int, y: Int): String? {
            if (!isReady()) return null
            acquireFreshImageIfAny()

            synchronized(frameLock) {
                val img = latestImage ?: return null
                val planes = try { img.planes } catch (_: Throwable) { null } ?: return null
                if (planes.isEmpty()) return null
                val buf = planes[0].buffer ?: return null

                val w = cachedW
                val h = cachedH
                val rs = cachedRowStride
                val ps = cachedPixelStride
                if (w <= 0 || h <= 0 || rs <= 0) return null

                val px = x.coerceIn(0, w - 1)
                val py = y.coerceIn(0, h - 1)
                val off = py * rs + px * ps
                if (off >= 0 && off + 2 < buf.limit()) {
                    val r = buf.get(off).toInt() and 0xFF
                    val g = buf.get(off + 1).toInt() and 0xFF
                    val b = buf.get(off + 2).toInt() and 0xFF
                    val rgb = (r shl 16) or (g shl 8) or b
                    return String.format("#%06X", rgb)
                }
            }
            return null
        }

        fun parseHexToInt(hex: String): Int {
            return try {
                val clean = if (hex.startsWith("#")) hex.trim() else "#${hex.trim()}"
                Color.parseColor(clean) and 0xFFFFFF
            } catch (_: Exception) {
                0xFF0000
            }
        }

        fun compareRgbInt(actualRgb: Int, targetRgb: Int, tolerance: Int): Boolean {
            val r1 = (actualRgb shr 16) and 0xFF
            val g1 = (actualRgb shr 8) and 0xFF
            val b1 = actualRgb and 0xFF

            val r2 = (targetRgb shr 16) and 0xFF
            val g2 = (targetRgb shr 8) and 0xFF
            val b2 = targetRgb and 0xFF

            return abs(r1 - r2) <= tolerance &&
                    abs(g1 - g2) <= tolerance &&
                    abs(b1 - b2) <= tolerance
        }

        fun matchColor(x: Int, y: Int, targetHex: String, tolerance: Int = 20): Boolean {
            val targetRgb = parseHexToInt(targetHex)
            return evaluateAreaInstantOrWait(
                x, y, targetRgb,
                false, 0,
                0, 0, 0,
                50, 2, tolerance, 0L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // PENTING: Jangan panggil startForeground di onCreate() agar tidak Crash SecurityException di Android 14+!
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopProjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            // Wajib panggil startForeground tepat sebelum getMediaProjection setelah izin RESULT_OK ada
            startForegroundServiceNotification()
            setupProjection(resultCode, data)
        } else if (!isReady()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun getExactPhysicalSize(wm: WindowManager): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        stopProjection()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = getExactPhysicalSize(wm)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = w
        screenHeight = h
        screenDensity = metrics.densityDpi

        val thread = HandlerThread("FastColorFrameThread", Process.THREAD_PRIORITY_MORE_FAVORABLE)
        thread.start()
        bgThread = thread
        val handler = Handler(thread.looper)
        bgHandler = handler

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjection()
            }
        }, mainHandler)

        // maxImages = 2 sudah sangat cukup untuk pertukaran buffer instan
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ _ ->
            if (latestImage == null) {
                acquireFreshImageIfAny()
            } else {
                synchronized(frameLock) {
                    frameLock.notifyAll()
                }
            }
        }, handler)
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FastAutoColorEngine",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun stopProjection() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        synchronized(frameLock) {
            try { latestImage?.close() } catch (_: Throwable) {}
            latestImage = null
            cachedW = 0
            cachedH = 0
        }
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        try { bgThread?.quitSafely() } catch (_: Throwable) {}
        bgThread = null
        bgHandler = null
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sys_sync_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Sync", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FastAuto Area Engine")
            .setContentText("Zero-Lag Direct Buffer Color Scanner Active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        stopProjection()
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
