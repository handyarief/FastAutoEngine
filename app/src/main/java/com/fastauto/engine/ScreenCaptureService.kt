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

        @Volatile var screenWidth = 1080
        @Volatile var screenHeight = 2400
        @Volatile var screenDensity = 400

        // Konfigurasi aktif yang dipantau secara real-time di RAM (< 0.01ms read)
        @Volatile private var watchX1 = 540
        @Volatile private var watchY1 = 1200
        @Volatile private var watchRgb1 = 0xFF0000
        @Volatile private var watchUseColor2 = false
        @Volatile private var watchLogic = 0
        @Volatile private var watchX2 = 540
        @Volatile private var watchY2 = 1200
        @Volatile private var watchRgb2 = 0x00FF00
        @Volatile private var watchRadius = 100
        @Volatile private var watchStep = 4
        @Volatile private var watchTol = 20

        // Cache hasil instan untuk pembacaan < 0.001 ms
        @Volatile var cachedMatchResult: Boolean = false
        @Volatile var lastFrameTimestampNs: Long = 0L
        @Volatile var isPickerOverlayOpen: Boolean = false

        // Buffer piksel tunggal untuk Crosshair Picker di MainActivity
        @Volatile private var pickerX = 540
        @Volatile private var pickerY = 1200
        @Volatile var cachedPickerRgb: Int = 0

        // Sinyal bangun instan untuk Mode Tunggu (Zero-CPU Wait)
        private val frameWaitLock = Object()

        // Buffer baris reusable agar 0% alokasi memori (Zero Garbage Collection)
        private var reusableRowBuffer = ByteArray(16384)

        @Volatile private var isHibernating = false
        private val hibernateRunnable = Runnable {
            if (!isPickerOverlayOpen) {
                enterHibernation()
            }
        }

        fun isReady(): Boolean = imageReader != null && mediaProjection != null

        fun updatePickerTarget(x: Int, y: Int) {
            pickerX = x
            pickerY = y
            wakeUpFromHibernation()
        }

        fun registerWatchConfig(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int
        ): Boolean {
            val changed = (watchX1 != x1 || watchY1 != y1 || watchRgb1 != rgb1 ||
                    watchUseColor2 != useColor2 || watchLogic != colorLogic ||
                    watchX2 != x2 || watchY2 != y2 || watchRgb2 != rgb2 ||
                    watchRadius != radius || watchStep != step || watchTol != tolerance)

            watchX1 = x1
            watchY1 = y1
            watchRgb1 = rgb1
            watchUseColor2 = useColor2
            watchLogic = colorLogic
            watchX2 = x2
            watchY2 = y2
            watchRgb2 = rgb2
            watchRadius = radius.coerceIn(1, 500)
            watchStep = step.coerceIn(1, 20)
            watchTol = tolerance.coerceIn(0, 255)

            wakeUpFromHibernation()
            return changed
        }

        fun evaluateAreaInstantOrWait(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int,
            timeoutMs: Long
        ): Boolean {
            val configChanged = registerWatchConfig(
                x1, y1, rgb1,
                useColor2, colorLogic,
                x2, y2, rgb2,
                radius, step, tolerance
            )

            // JALUR SUPER INSTAN (< 0.001 ms): Baca langsung hasil primitif @Volatile dari RAM
            if (!configChanged && !isHibernating && cachedMatchResult) {
                return true
            }
            if (timeoutMs <= 0L && !configChanged && !isHibernating) {
                return cachedMatchResult
            }

            // Jika perlu menunggu warna muncul di area (Sesuai aturan In-Block Wait Automate)
            val waitBudget = if (timeoutMs <= 0L) 35L else timeoutMs.coerceAtMost(8000L)
            val deadline = System.nanoTime() + (waitBudget * 1_000_000L)

            synchronized(frameWaitLock) {
                while (true) {
                    if (cachedMatchResult) return true
                    val remainingNs = deadline - System.nanoTime()
                    if (remainingNs <= 0L) break
                    val waitMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                    try {
                        frameWaitLock.wait(waitMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            return cachedMatchResult
        }

        private fun wakeUpFromHibernation() {
            mainHandler.removeCallbacks(hibernateRunnable)
            mainHandler.postDelayed(hibernateRunnable, 3500L)
            if (isHibernating) {
                isHibernating = false
                mainHandler.post {
                    try {
                        virtualDisplay?.surface = imageReader?.surface
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun enterHibernation() {
            if (!isHibernating && virtualDisplay != null) {
                isHibernating = true
                try {
                    virtualDisplay?.surface = null
                } catch (_: Throwable) {}
            }
        }

        private fun processIncomingFrame(reader: ImageReader) {
            val img = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return

            // Wajib tutup Image di blok finally dalam mikrodetik yang sama agar GPU 0% Lag!
            try {
                val planes = img.planes
                if (planes.isNullOrEmpty()) return
                val plane = planes[0]
                val buffer = plane.buffer ?: return
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = img.width
                val height = img.height
                val limit = buffer.limit()

                // 1. Update warna untuk Crosshair Picker bila sedang aktif
                if (isPickerOverlayOpen) {
                    val px = pickerX.coerceIn(0, width - 1)
                    val py = pickerY.coerceIn(0, height - 1)
                    val pOff = py * rowStride + px * pixelStride
                    if (pOff >= 0 && pOff + 2 < limit) {
                        val r = buffer.get(pOff).toInt() and 0xFF
                        val g = buffer.get(pOff + 1).toInt() and 0xFF
                        val b = buffer.get(pOff + 2).toInt() and 0xFF
                        cachedPickerRgb = (r shl 16) or (g shl 8) or b
                    }
                }

                // 2. Pindai Area 1 & Area 2 secara Bulk Array (Super Cepat & Zero-Allocation)
                val rad = watchRadius
                val stp = watchStep
                val tol = watchTol
                val use2 = watchUseColor2
                val logic = watchLogic

                val m1 = scanBufferAreaBulk(
                    buffer, width, height, rowStride, pixelStride, limit,
                    watchX1, watchY1, rad, stp, watchRgb1, tol
                )

                val finalMatch = if (!use2) {
                    m1
                } else if (logic == 0) {
                    if (!m1) false else scanBufferAreaBulk(
                        buffer, width, height, rowStride, pixelStride, limit,
                        watchX2, watchY2, rad, stp, watchRgb2, tol
                    )
                } else {
                    if (m1) true else scanBufferAreaBulk(
                        buffer, width, height, rowStride, pixelStride, limit,
                        watchX2, watchY2, rad, stp, watchRgb2, tol
                    )
                }

                cachedMatchResult = finalMatch
                lastFrameTimestampNs = System.nanoTime()

                if (finalMatch) {
                    synchronized(frameWaitLock) {
                        frameWaitLock.notifyAll()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    img.close()
                } catch (_: Throwable) {}
            }
        }

        private fun scanBufferAreaBulk(
            buffer: java.nio.ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            limit: Int,
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

            // Cek titik tengah persis terlebih dahulu (Fastest Hit)
            val centerOff = cy * rowStride + cx * pixelStride
            if (centerOff >= 0 && centerOff + 2 < limit) {
                val cr = buffer.get(centerOff).toInt() and 0xFF
                val cg = buffer.get(centerOff + 1).toInt() and 0xFF
                val cb = buffer.get(centerOff + 2).toInt() and 0xFF
                if (abs(cr - r2) <= tolerance && abs(cg - g2) <= tolerance && abs(cb - b2) <= tolerance) {
                    return true
                }
            }

            val startX = (cx - radius).coerceAtLeast(0)
            val endX = (cx + radius).coerceAtMost(width - 1)
            val startY = (cy - radius).coerceAtLeast(0)
            val endY = (cy + radius).coerceAtMost(height - 1)

            val bytesNeeded = (endX - startX + 1) * pixelStride
            if (bytesNeeded <= 0) return false
            if (reusableRowBuffer.size < bytesNeeded) {
                reusableRowBuffer = ByteArray(bytesNeeded + 1024)
            }
            val rowArr = reusableRowBuffer

            for (y in startY..endY step step) {
                val rowStartOff = y * rowStride + startX * pixelStride
                if (rowStartOff < 0 || rowStartOff + bytesNeeded > limit) continue

                // Satu kali JNI Bulk Copy per baris (10x lebih cepat daripada buffer.get per piksel)
                buffer.position(rowStartOff)
                buffer.get(rowArr, 0, bytesNeeded)

                var localIdx = 0
                val maxLocalIdx = bytesNeeded - 3
                val strideStep = step * pixelStride
                while (localIdx <= maxLocalIdx) {
                    val r1 = rowArr[localIdx].toInt() and 0xFF
                    val g1 = rowArr[localIdx + 1].toInt() and 0xFF
                    val b1 = rowArr[localIdx + 2].toInt() and 0xFF
                    if (abs(r1 - r2) <= tolerance &&
                        abs(g1 - g2) <= tolerance &&
                        abs(b1 - b2) <= tolerance
                    ) {
                        return true
                    }
                    localIdx += strideStep
                }
            }
            return false
        }

        fun getPixelHex(x: Int, y: Int): String? {
            if (!isReady()) return null
            updatePickerTarget(x, y)
            return String.format("#%06X", cachedPickerRgb and 0xFFFFFF)
        }

        fun parseHexToInt(hex: String): Int {
            return try {
                val clean = if (hex.startsWith("#")) hex else "#$hex"
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
                100, 4, tolerance, 0L
            )
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

        // Gunakan maxImages = 3 dan langsung close() agar GPU tidak pernah lag / frame-drop
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            processIncomingFrame(r)
        }, handler)
        imageReader = reader

        isHibernating = false
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FastAutoColorEngine",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        mainHandler.removeCallbacks(hibernateRunnable)
        mainHandler.postDelayed(hibernateRunnable, 3500L)
    }

    private fun stopProjection() {
        mainHandler.removeCallbacks(hibernateRunnable)
        isHibernating = false
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
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
            .setContentText("Zero-Lag Area Color Scanner Ready")
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
