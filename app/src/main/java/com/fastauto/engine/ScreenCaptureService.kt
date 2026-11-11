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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
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

        private var cachedBuffer: ByteArray? = null
        private var cachedRowStride = 0
        private var cachedPixelStride = 4
        private val lock = Any()

        fun isReady(): Boolean = imageReader != null && cachedBuffer != null

        private fun pullLatestFrame(reader: ImageReader) {
            var image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
            while (true) {
                val newer = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                if (newer != null) {
                    image.close()
                    image = newer
                } else {
                    break
                }
            }
            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val size = buffer.remaining()
                synchronized(lock) {
                    if (cachedBuffer == null || cachedBuffer!!.size < size) {
                        cachedBuffer = ByteArray(size)
                    }
                    buffer.get(cachedBuffer!!, 0, size)
                    cachedRowStride = plane.rowStride
                    cachedPixelStride = plane.pixelStride
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }

        fun getPixelHex(x: Int, y: Int): String? {
            imageReader?.let { pullLatestFrame(it) }
            synchronized(lock) {
                val data = cachedBuffer ?: return null
                if (x < 0 || y < 0 || x >= screenWidth || y >= screenHeight) return null
                val offset = y * cachedRowStride + x * cachedPixelStride
                if (offset < 0 || offset + 2 >= data.size) return null

                val r = data[offset].toInt() and 0xFF
                val g = data[offset + 1].toInt() and 0xFF
                val b = data[offset + 2].toInt() and 0xFF

                return String.format("#%02X%02X%02X", r, g, b)
            }
        }

        fun matchColor(x: Int, y: Int, targetHex: String, tolerance: Int = 15): Boolean {
            val currentHex = getPixelHex(x, y) ?: return false
            return compareHex(currentHex, targetHex, tolerance)
        }

        fun compareHex(currentHex: String, targetHex: String, tolerance: Int): Boolean {
            return try {
                val c1 = Color.parseColor(currentHex)
                val cleanTarget = if (targetHex.startsWith("#")) targetHex else "#$targetHex"
                val c2 = Color.parseColor(cleanTarget)
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
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        bgHandler = Handler(handlerThread!!.looper)

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // Wajib mendaftarkan callback pada Android 14+
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjection()
            }
        }, bgHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader ->
                pullLatestFrame(reader)
            }, bgHandler)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FastColorCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )
    }

    private fun stopProjection() {
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
        val channelId = "fast_auto_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FastAuto Engine Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FastAuto Engine Aktif")
            .setContentText("Mesin pembaca warna real-time siap bekerja")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
