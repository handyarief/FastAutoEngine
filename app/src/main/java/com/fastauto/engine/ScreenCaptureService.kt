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
import android.os.SystemClock
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

        // Menyimpan frame terakhir HANYA sebagai fallback mikro jika tidak ada frame baru saat dieksekusi,
        // namun tanpa listener aktif di background agar 0% CPU saat idle (seperti OFF).
        private var latestImage: android.media.Image? = null
        @Volatile private var cachedW = 0
        @Volatile private var cachedH = 0
        @Volatile private var cachedRowStride = 0
        @Volatile private var cachedPixelStride = 4

        // Reusable buffer kecil khusus untuk 1 baris area ROI (maks radius 500 -> lebar 1001 piksel * 4 byte = ~4 KB).
        // Mencegah alokasi memori berulang (0% GC Pause) dan menghindari JNI call per-piksel.
        private val roiRowBuffer = ByteArray(4096)

        fun isReady(): Boolean = imageReader != null && mediaProjection != null

        fun updatePickerTarget(x: Int, y: Int) {
            // Mode On-Demand: 0% CPU saat idle
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
         * Mengambil frame terbaru HANYA SAAT DIPANGGIL (On-Demand).
         * Jika ada frame baru dari GPU, frame lama langsung di-close() agar antrean GPU selalu lega
         * dan layar HP tidak pernah ngelag/patah-patah.
         */
        private fun acquireFreshImageOnDemand() {
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
                        try {
                            latestImage?.close()
                        } catch (_: Throwable) {}
                        latestImage = newImg
                        cachedW = newImg.width
                        cachedH = newImg.height
                        cachedRowStride = planes[0].rowStride
                        cachedPixelStride = planes[0].pixelStride
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
            if (!isReady()) return false

            val rad = radius.coerceIn(1, 500)
            val stp = step.coerceIn(1, 20)
            val tol = tolerance.coerceIn(0, 255)

            // 1. Langsung ON: Tarik frame terbaru tepat saat dipanggil Automate
            acquireFreshImageOnDemand()

            // 2. Deteksi HANYA pada kotak Area target (< 0.01ms)
            if (checkCurrentAreaOnly(x1, y1, rgb1, useColor2, colorLogic, x2, y2, rgb2, rad, stp, tol)) {
                return true
            }

            // Jika mode instan (timeout == 0), langsung selesai & kembali diam seperti OFF
            if (timeoutMs <= 0L) {
                return false
            }

            // 3. Jika mode tunggu (timeout > 0), aktifkan sinyal tunggu sementara hanya selama batas timeout
            val reader = imageReader ?: return false
            val h = bgHandler
            val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtMost(8000L)

            try {
                if (h != null) {
                    reader.setOnImageAvailableListener({ _ ->
                        synchronized(frameLock) {
                            frameLock.notifyAll()
                        }
                    }, h)
                }

                while (SystemClock.elapsedRealtime() < deadline) {
                    acquireFreshImageOnDemand()
                    if (checkCurrentAreaOnly(x1, y1, rgb1, useColor2, colorLogic, x2, y2, rgb2, rad, stp, tol)) {
                        return true
                    }
                    val remMs = (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 12L)
                    synchronized(frameLock) {
                        try {
                            frameLock.wait(remMs)
                        } catch (_: InterruptedException) {
                            return false
                        }
                    }
                }
            } finally {
                // PENTING: Matikan kembali listener begitu selesai agar kembali 0% CPU (seperti OFF)
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (_: Throwable) {}
            }
            return false
        }

        private fun checkCurrentAreaOnly(
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
                if (w <= 0 || h <= 0 || rs <= 0 || ps <= 0) return false

                val m1 = scanTargetAreaROI(buf, w, h, rs, ps, x1, y1, radius, step, rgb1, tolerance)
                return if (!useColor2) {
                    m1
                } else if (colorLogic == 0) {
                    if (!m1) false else scanTargetAreaROI(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
                } else {
                    if (m1) true else scanTargetAreaROI(buf, w, h, rs, ps, x2, y2, radius, step, rgb2, tolerance)
                }
            }
        }

        /**
         * Memindai HANYA kotak area target (Region of Interest / ROI) menggunakan Bulk Row-Slice.
         * Tidak pernah memindai 1 layar penuh, dan menghindari panggilan JNI ByteBuffer.get() ribuan kali.
         */
        private fun scanTargetAreaROI(
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

            // 1. Cek 1 titik pusat terlebih dahulu (0.0001 ms)
            val centerOff = cy * rowStride + cx * pixelStride
            if (centerOff >= 0 && centerOff + 2 < limit) {
                val cr = buf.get(centerOff).toInt() and 0xFF
                val cg = buf.get(centerOff + 1).toInt() and 0xFF
                val cb = buf.get(centerOff + 2).toInt() and 0xFF
                if (abs(cr - r2) <= tolerance && abs(cg - g2) <= tolerance && abs(cb - b2) <= tolerance) {
                    return true
                }
            }

            // 2. Hitung batas kotak area deteksi saja (bukan 1 layar)
            val startX = (cx - radius).coerceAtLeast(0)
            val endX = (cx + radius).coerceAtMost(width - 1)
            val startY = (cy - radius).coerceAtLeast(0)
            val endY = (cy + radius).coerceAtMost(height - 1)

            if (startX > endX || startY > endY) return false

            val spanPixels = endX - startX + 1
            val bytesNeeded = spanPixels * pixelStride
            val rowArray = if (bytesNeeded <= roiRowBuffer.size) roiRowBuffer else ByteArray(bytesNeeded)
            val strideStep = step * pixelStride

            try {
                for (y in startY..endY step step) {
                    val rowStartOff = y * rowStride + startX * pixelStride
                    if (rowStartOff < 0 || rowStartOff + bytesNeeded > limit) continue

                    // Salin 1 potongan baris pendek area target sekaligus dari Native ke RAM CPU (Sangat Cepat)
                    buf.position(rowStartOff)
                    buf.get(rowArray, 0, bytesNeeded)

                    var localOff = 0
                    val maxLocalOff = bytesNeeded - 3
                    while (localOff <= maxLocalOff) {
                        val r1 = rowArray[localOff].toInt() and 0xFF
                        val g1 = rowArray[localOff + 1].toInt() and 0xFF
                        val b1 = rowArray[localOff + 2].toInt() and 0xFF
                        if (abs(r1 - r2) <= tolerance &&
                            abs(g1 - g2) <= tolerance &&
                            abs(b1 - b2) <= tolerance
                        ) {
                            return true
                        }
                        localOff += strideStep
                    }
                }
            } finally {
                try {
                    buf.position(0)
                } catch (_: Throwable) {}
            }
            return false
        }

        fun getPixelHex(x: Int, y: Int): String? {
            if (!isReady()) return null
            acquireFreshImageOnDemand()

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

        val thread = HandlerThread("FastColorFrameThread", Process.THREAD_PRIORITY_BACKGROUND)
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

        // Gunakan maxImages = 3 dan TANPA listener aktif permanen agar antrean GPU bebas lag
        // dan thread benar-benar tidur (0% CPU) saat tidak dipanggil Automate.
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
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
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Throwable) {}
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
            .setContentText("On-Demand Area Scanner Ready (0% Idle CPU)")
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
