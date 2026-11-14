package com.fastauto.engine

import android.app.*
import android.content.Context
import android.content.Intent
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
        private var handlerThread: HandlerThread? = null
        private var bgHandler: Handler? = null

        var screenWidth = 1080
        var screenHeight = 2400
        var screenDensity = 400

        // Simpan Image terakhir langsung di memori native (Zero-Copy)
        private var latestImage: Image? = null
        private val lock = Any()

        fun isReady(): Boolean = synchronized(lock) { imageReader != null && latestImage != null }

        private fun updateLatestFrame(reader: ImageReader) {
            val newImage = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
            synchronized(lock) {
                try { latestImage?.close() } catch (_: Exception) {}
                latestImage = newImage
            }
        }

        // Baca RGB dalam bentuk Int murni (0xRRGGBB) hanya dalam ~0.02 ms!
        fun getPixelRgbInt(x: Int, y: Int): Int? {
            synchronized(lock) {
                val img = latestImage ?: return null
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
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun getPixelHex(x: Int, y: Int): String? {
            val rgb = getPixelRgbInt(x, y) ?: return null
            return String.format("#%06X", rgb and 0xFFFFFF)
        }

        // Konversi Hex ke Int sekali saja sebelum pengecekan
        fun parseHexToInt(hex: String): Int {
            return try {
                val clean = if (hex.startsWith("#")) hex else "#$hex"
                Color.parseColor(clean) and 0xFFFFFF
            } catch (_: Exception) {
                0
            }
        }

        // Perbandingan Bitwise super cepat (tanpa alokasi String / parseColor berulang)
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
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")
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

        handlerThread = HandlerThread("SysDisplayWorker").apply { start() }
        bgHandler = Handler(handlerThread!!.looper)

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjection()
            }
        }, bgHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                updateLatestFrame(reader)
            }, bgHandler)
        }

        // Gunakan nama sistem generik agar tidak mudah di-flag oleh aplikasi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WifiDisplay",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )
    }

    private fun stopProjection() {
        synchronized(lock) {
            try { latestImage?.close() } catch (_: Exception) {}
            latestImage = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        bgHandler = null
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
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
