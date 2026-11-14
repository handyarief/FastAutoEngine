package com.fastauto.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        var instance: ScreenCaptureService? = null
        private var mediaProjection: MediaProjection? = null
        private var imageReader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null

        var screenWidth = 1080
        var screenHeight = 2400
        var screenDensity = 400

        // Simpan Image terakhir langsung di memori native (Zero-Copy & On-Demand)
        private var latestImage: Image? = null
        private val lock = Any()

        fun isReady(): Boolean = synchronized(lock) { imageReader != null }

        /**
         * Menarik frame terbaru dari buffer GPU HANYA saat dibutuhkan (0% CPU saat idle).
         * Jika tidak ada frame baru sejak pengecekan terakhir, gunakan latestImage yang sudah ada.
         */
        private fun pullLatestFrameLocked(): Image? {
            val reader = imageReader ?: return null
            try {
                val newImage = reader.acquireLatestImage()
                if (newImage != null) {
                    try { latestImage?.close() } catch (_: Throwable) {}
                    latestImage = newImage
                }
            } catch (_: Throwable) {}
            return latestImage
        }

        // Baca RGB dalam bentuk Int murni (0xRRGGBB) hanya dalam ~0.01 ms!
        fun getPixelRgbInt(x: Int, y: Int): Int? {
            synchronized(lock) {
                val img = pullLatestFrameLocked() ?: return null
                if (x < 0 || y < 0 || x >= img.width || y >= img.height) return null
                return try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    if (offset < 0 || offset + 2 >= buffer.limit()) return null

                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    (r shl 16) or (g shl 8) or b
                } catch (_: Throwable) {
                    null
                }
            }
        }

        /**
         * Baca 2 titik sekaligus dalam 1x Lock & 1x Tarik Frame (Super Instan untuk Mode 2 Warna)
         */
        fun getTwoPixelsRgbInt(x1: Int, y1: Int, x2: Int, y2: Int): Pair<Int?, Int?> {
            synchronized(lock) {
                val img = pullLatestFrameLocked() ?: return Pair(null, null)
                return try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val limit = buffer.limit()
                    val w = img.width
                    val h = img.height

                    var rgb1: Int? = null
                    if (x1 in 0 until w && y1 in 0 until h) {
                        val off1 = y1 * rowStride + x1 * pixelStride
                        if (off1 >= 0 && off1 + 2 < limit) {
                            val r = buffer.get(off1).toInt() and 0xFF
                            val g = buffer.get(off1 + 1).toInt() and 0xFF
                            val b = buffer.get(off1 + 2).toInt() and 0xFF
                            rgb1 = (r shl 16) or (g shl 8) or b
                        }
                    }

                    var rgb2: Int? = null
                    if (x2 in 0 until w && y2 in 0 until h) {
                        val off2 = y2 * rowStride + x2 * pixelStride
                        if (off2 >= 0 && off2 + 2 < limit) {
                            val r = buffer.get(off2).toInt() and 0xFF
                            val g = buffer.get(off2 + 1).toInt() and 0xFF
                            val b = buffer.get(off2 + 2).toInt() and 0xFF
                            rgb2 = (r shl 16) or (g shl 8) or b
                        }
                    }

                    Pair(rgb1, rgb2)
                } catch (_: Throwable) {
                    Pair(null, null)
                }
            }
        }

        /**
         * Pemindaian area cepat dalam 1x Lock tanpa overhead sinkronisasi berulang
         */
        fun scanRamAreaFast(
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetRgb: Int,
            tolerance: Int
        ): Boolean {
            synchronized(lock) {
                val img = pullLatestFrameLocked() ?: return false
                return try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val limit = buffer.limit()

                    val startX = (centerX - radius).coerceAtLeast(0)
                    val endX = (centerX + radius).coerceAtMost(img.width - 1)
                    val startY = (centerY - radius).coerceAtLeast(0)
                    val endY = (centerY + radius).coerceAtMost(img.height - 1)

                    val r2 = (targetRgb shr 16) and 0xFF
                    val g2 = (targetRgb shr 8) and 0xFF
                    val b2 = targetRgb and 0xFF

                    for (y in startY..endY step step) {
                        val rowOff = y * rowStride
                        for (x in startX..endX step step) {
                            val offset = rowOff + x * pixelStride
                            if (offset >= 0 && offset + 2 < limit) {
                                val r1 = buffer.get(offset).toInt() and 0xFF
                                val g1 = buffer.get(offset + 1).toInt() and 0xFF
                                val b1 = buffer.get(offset + 2).toInt() and 0xFF
                                if (abs(r1 - r2) <= tolerance &&
                                    abs(g1 - g2) <= tolerance &&
                                    abs(b1 - b2) <= tolerance
                                ) {
                                    return true
                                }
                            }
                        }
                    }
                    false
                } catch (_: Throwable) {
                    false
                }
            }
        }

        fun getPixelHex(x: Int, y: Int): String? {
            val rgb = getPixelRgbInt(x, y) ?: return null
            return String.format("#%06X", rgb and 0xFFFFFF)
        }

        fun parseHexToInt(hex: String): Int {
            return try {
                val clean = if (hex.startsWith("#")) hex else "#$hex"
                Color.parseColor(clean) and 0xFFFFFF
            } catch (_: Exception) {
                0
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

        fun compareHex(currentHex: String, targetHex: String, tolerance: Int): Boolean {
            return compareRgbInt(parseHexToInt(currentHex), parseHexToInt(targetHex), tolerance)
        }

        fun matchColor(x: Int, y: Int, targetHex: String, tolerance: Int = 15): Boolean {
            val actualRgb = getPixelRgbInt(x, y) ?: return false
            val targetRgb = parseHexToInt(targetHex)
            return compareRgbInt(actualRgb, targetRgb, tolerance)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
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

        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            setupProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        stopProjection()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val mainHandler = Handler(Looper.getMainLooper())
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjection()
            }
        }, mainHandler)

        synchronized(lock) {
            // maxImages = 2 tanpa listener: GPU hanya menimpa buffer secara pasif tanpa membangunkan CPU
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WifiDisplay",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Tarik 1 frame awal setelah 60ms agar status langsung siap
        mainHandler.postDelayed({
            synchronized(lock) { pullLatestFrameLocked() }
        }, 60L)
    }

    private fun stopProjection() {
        synchronized(lock) {
            try { latestImage?.close() } catch (_: Throwable) {}
            latestImage = null
            try { imageReader?.close() } catch (_: Throwable) {}
            imageReader = null
        }
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sys_sync_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Sync", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service")
            .setContentText("Running in background")
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
