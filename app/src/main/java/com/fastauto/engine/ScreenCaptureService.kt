package com.fastauto.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
        var screenWidth = 1080
        var screenHeight = 2400
        var screenDensity = 400

        fun isReady(): Boolean = imageReader != null

        // Membaca warna piksel langsung dari ByteBuffer RAM (Super Cepat < 2ms)
        fun getPixelHex(x: Int, y: Int): String? {
            val reader = imageReader ?: return null
            val image = reader.acquireLatestImage() ?: return null
            try {
                if (x < 0 || y < 0 || x >= image.width || y >= image.height) return null
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val offset = y * rowStride + x * pixelStride

                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF

                return String.format("#%02X%02X%02X", r, g, b)
            } catch (e: Exception) {
                return null
            } finally {
                image.close()
            }
        }

        // Cek kecocokan warna dengan toleransi kemiripan
        fun matchColor(x: Int, y: Int, targetHex: String, tolerance: Int = 15): Boolean {
            val currentHex = getPixelHex(x, y) ?: return false
            return try {
                val c1 = Color.parseColor(currentHex)
                val c2 = Color.parseColor(if (targetHex.startsWith("#")) targetHex else "#$targetHex")
                val rDiff = abs(Color.red(c1) - Color.red(c2))
                val gDiff = abs(Color.green(c1) - Color.green(c2))
                val bDiff = abs(Color.blue(c1) - Color.blue(c2))
                (rDiff <= tolerance && gDiff <= tolerance && bDiff <= tolerance)
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")
        if (resultCode == Activity.RESULT_OK && data != null) {
            setupProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FastColorCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startForegroundServiceNotification() {
        val channelId = "fast_auto_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FastAuto Engine Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FastAuto Engine Aktif")
            .setContentText("Mesin pembaca warna & blok siap bekerja")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
