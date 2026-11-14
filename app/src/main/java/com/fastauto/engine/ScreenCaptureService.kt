
package com.fastauto.engine

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {

        private const val TAG = "FastAutoCapture"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fastauto_capture"
        private const val MAX_IMAGES = 3
        private const val INVALID_RGB = -1

        @Volatile
        var instance: ScreenCaptureService? = null

        @Volatile
        private var mediaProjection: MediaProjection? = null

        @Volatile
        private var imageReader: ImageReader? = null

        @Volatile
        private var virtualDisplay: VirtualDisplay? = null

        @Volatile
        private var handlerThread: HandlerThread? = null

        @Volatile
        private var bgHandler: Handler? = null

        @Volatile
        var screenWidth = 1080

        @Volatile
        var screenHeight = 2400

        @Volatile
        var screenDensity = 400

        /*
         * Satu frame terbaru dipertahankan di memori.
         * Frame sebelumnya langsung ditutup setelah frame baru diterima.
         */
        private var latestImage: Image? = null

        private var frameSequence = 0L
        private var lastFrameReceivedNs = 0L

        private val frameLock = Any()

        data class FrameInfo(
            val sequence: Long,
            val width: Int,
            val height: Int,
            val imageTimestampNs: Long,
            val receivedAgeMs: Long
        )

        /*
         * Hasil pembacaan beberapa koordinat dari SATU frame.
         * INVALID_RGB (-1) berarti koordinat tidak valid.
         */
        data class PixelBatch(
            val rgbValues: IntArray,
            val frameSequence: Long,
            val imageTimestampNs: Long,
            val receivedAgeMs: Long,
            val width: Int,
            val height: Int
        )

        // Status mesin capture.
        fun isReady(): Boolean {
            return synchronized(frameLock) {
                imageReader != null && latestImage != null
            }
        }

        // Umur sejak frame terakhir diterima oleh aplikasi.
        fun getFrameInfo(): FrameInfo? {
            return synchronized(frameLock) {
                val image = latestImage ?: return@synchronized null
                val receivedAt = lastFrameReceivedNs

                val ageNs = if (receivedAt > 0L) {
                    (SystemClock.elapsedRealtimeNanos() - receivedAt)
                        .coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }

                FrameInfo(
                    sequence = frameSequence,
                    width = image.width,
                    height = image.height,
                    imageTimestampNs = image.timestamp,
                    receivedAgeMs = ageNs / 1_000_000L
                )
            }
        }

        /*
         * Pemeriksaan kesegaran berdasarkan waktu penerimaan frame,
         * bukan membandingkan clock yang mungkin berbeda timebase.
         */
        fun isFrameFresh(maxAgeMs: Long = 1000L): Boolean {
            if (maxAgeMs < 0L) return false

            return synchronized(frameLock) {
                if (latestImage == null || lastFrameReceivedNs <= 0L) {
                    return@synchronized false
                }

                val ageNs = SystemClock.elapsedRealtimeNanos() -
                    lastFrameReceivedNs

                ageNs >= 0L && ageNs <= maxAgeMs * 1_000_000L
            }
        }

        // Pembacaan RGB langsung dari buffer.
        private fun readPixelFromBuffer(
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int
        ): Int? {

            if (x !in 0 until width || y !in 0 until height) {
                return null
            }

            if (pixelStride < 3 || rowStride <= 0) {
                return null
            }

            val offset = buffer.position().toLong() +
                y.toLong() * rowStride +
                x.toLong() * pixelStride

            if (offset < 0L || offset + 2L >= buffer.limit()) {
                return null
            }

            return try {
                val index = offset.toInt()

                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF

                (r shl 16) or (g shl 8) or b

            } catch (_: Exception) {
                null
            }
        }

        private fun readPixelLocked(
            image: Image,
            x: Int,
            y: Int
        ): Int? {

            if (x < 0 || y < 0 ||
                x >= image.width || y >= image.height
            ) {
                return null
            }

            return try {
                val plane = image.planes.firstOrNull()
                    ?: return null

                readPixelFromBuffer(
                    plane.buffer,
                    plane.rowStride,
                    plane.pixelStride,
                    x,
                    y,
                    image.width,
                    image.height
                )

            } catch (_: Exception) {
                null
            }
        }

        // API pembacaan satu titik untuk kompatibilitas kode lama.
        fun getPixelRgbInt(x: Int, y: Int): Int? {
            return synchronized(frameLock) {
                val image = latestImage ?: return@synchronized null
                readPixelLocked(image, x, y)
            }
        }

        /*
         * API baru:
         * Membaca beberapa koordinat dari frame yang SAMA.
         *
         * Format coordinates:
         * intArrayOf(x1, y1, x2, y2, ...)
         */
        fun getPixelRgbBatch(coordinates: IntArray): PixelBatch? {

            if (coordinates.isEmpty() ||
                coordinates.size % 2 != 0
            ) {
                return null
            }

            return synchronized(frameLock) {

                val image = latestImage ?: return@synchronized null

                val values = IntArray(coordinates.size / 2)

                for (i in values.indices) {
                    val x = coordinates[i * 2]
                    val y = coordinates[i * 2 + 1]

                    values[i] = readPixelLocked(image, x, y)
                        ?: INVALID_RGB
                }

                val ageNs = if (lastFrameReceivedNs > 0L) {
                    (SystemClock.elapsedRealtimeNanos() -
                        lastFrameReceivedNs).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }

                PixelBatch(
                    rgbValues = values,
                    frameSequence = frameSequence,
                    imageTimestampNs = image.timestamp,
                    receivedAgeMs = ageNs / 1_000_000L,
                    width = image.width,
                    height = image.height
                )
            }
        }

        fun getPixelHex(x: Int, y: Int): String? {
            val rgb = getPixelRgbInt(x, y) ?: return null

            return String.format(
                Locale.US,
                "#%06X",
                rgb and 0xFFFFFF
            )
        }

        /*
         * Pencarian area memakai satu frame dan satu lock.
         * Tidak mengambil screenshot berulang untuk setiap titik.
         */
        fun findPixelInArea(
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetRgb: Int,
            tolerance: Int
        ): Pair<Int, Int>? {

            if (radius < 0 || tolerance !in 0..255) {
                return null
            }

            val sampleStep = step.coerceAtLeast(1)

            return synchronized(frameLock) {

                val image = latestImage ?: return@synchronized null
                val plane = image.planes.firstOrNull()
                    ?: return@synchronized null

                val buffer = plane.buffer

                val left = (centerX - radius).coerceAtLeast(0)
                val top = (centerY - radius).coerceAtLeast(0)

                val right = (centerX + radius)
                    .coerceAtMost(image.width - 1)

                val bottom = (centerY + radius)
                    .coerceAtMost(image.height - 1)

                if (left > right || top > bottom) {
                    return@synchronized null
                }

                for (y in top..bottom step sampleStep) {
                    for (x in left..right step sampleStep) {

                        val rgb = readPixelFromBuffer(
                            buffer,
                            plane.rowStride,
                            plane.pixelStride,
                            x,
                            y,
                            image.width,
                            image.height
                        ) ?: continue

                        if (compareRgbInt(
                                rgb,
                                targetRgb,
                                tolerance
                            )
                        ) {
                            return@synchronized Pair(x, y)
                        }
                    }
                }

                null
            }
        }

        /*
         * Parsing HEX ketat.
         * Mendukung #RRGGBB dan #AARRGGBB.
         */
        fun parseHexToIntOrNull(hex: String): Int? {

            val clean = hex.trim().removePrefix("#")

            if (clean.length != 6 && clean.length != 8) {
                return null
            }

            val value = clean.toLongOrNull(16) ?: return null

            return value.toInt() and 0xFFFFFF
        }

        // Kompatibilitas dengan kode lama.
        // Untuk validasi baru, gunakan parseHexToIntOrNull().
        fun parseHexToInt(hex: String): Int {
            return parseHexToIntOrNull(hex) ?: 0
        }

        // Perbandingan tiap kanal RGB.
        fun compareRgbInt(
            actualRgb: Int,
            targetRgb: Int,
            tolerance: Int
        ): Boolean {

            if (tolerance !in 0..255) return false
            if (actualRgb !in 0..0xFFFFFF) return false
            if (targetRgb !in 0..0xFFFFFF) return false

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

        fun compareHex(
            currentHex: String,
            targetHex: String,
            tolerance: Int
        ): Boolean {

            val current = parseHexToIntOrNull(currentHex)
                ?: return false

            val target = parseHexToIntOrNull(targetHex)
                ?: return false

            return compareRgbInt(current, target, tolerance)
        }

        fun matchColor(
            x: Int,
            y: Int,
            targetHex: String,
            tolerance: Int = 15
        ): Boolean {

            val actual = getPixelRgbInt(x, y) ?: return false
            val target = parseHexToIntOrNull(targetHex)
                ?: return false

            return compareRgbInt(actual, target, tolerance)
        }

        /*
         * Dipanggil setiap ImageReader menerima frame baru.
         * acquireLatestImage membuang antrean frame yang lebih lama.
         */
        private fun updateLatestFrame(reader: ImageReader) {

            val newImage = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return

            synchronized(frameLock) {

                if (imageReader !== reader) {
                    try {
                        newImage.close()
                    } catch (_: Exception) {
                    }

                    return@synchronized
                }

                try {
                    latestImage?.close()
                } catch (_: Exception) {
                }

                latestImage = newImage
                frameSequence++

                lastFrameReceivedNs =
                    SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    private val lifecycleLock = Any()

    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()

        instance = this
        startForegroundServiceNotification()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == "STOP_SERVICE") {

            stopProjection()

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)

            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(
            "RESULT_CODE",
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                "DATA",
                Intent::class.java
            )
        } else {
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {

            try {
                setupProjection(resultCode, data)

            } catch (e: Exception) {

                Log.e(TAG, "Screen capture gagal dimulai", e)

                stopProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun setupProjection(
        resultCode: Int,
        data: Intent
    ) {

        stopProjection()

        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)

        val worker = HandlerThread(
            "FastAutoCaptureWorker"
        ).apply {
            start()
        }

        var newReader: ImageReader? = null
        var newProjection: MediaProjection? = null
        var attached = false

        try {

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )

            newReader = reader

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val projection = manager.getMediaProjection(
                resultCode,
                data
            )

            newProjection = projection

            val handler = Handler(worker.looper)

            val callback = object : MediaProjection.Callback() {

                override fun onStop() {
                    stopProjection(
                        expectedProjection = projection,
                        stopProjectionToken = false
                    )
                }
            }

            synchronized(lifecycleLock) {

                handlerThread = worker
                bgHandler = handler

                mediaProjection = projection
                projectionCallback = callback

                screenWidth = width
                screenHeight = height
                screenDensity = density

                synchronized(frameLock) {

                    imageReader = reader

                    try {
                        latestImage?.close()
                    } catch (_: Exception) {
                    }

                    latestImage = null
                    lastFrameReceivedNs = 0L
                }

                attached = true
            }

            // Callback didaftarkan sebelum VirtualDisplay dibuat.
            projection.registerCallback(callback, handler)

            reader.setOnImageAvailableListener(
                { source ->
                    updateLatestFrame(source)
                },
                handler
            )

            val display = projection.createVirtualDisplay(
                "FastAutoScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            ) ?: throw IllegalStateException(
                "VirtualDisplay gagal dibuat"
            )

            synchronized(lifecycleLock) {

                if (mediaProjection === projection) {
                    virtualDisplay = display
                } else {
                    display.release()
                }
            }

            Log.i(
                TAG,
                "Capture aktif: ${width}x${height}, maxImages=$MAX_IMAGES"
            )

        } catch (e: Exception) {

            if (attached) {

                newProjection?.let {
                    stopProjection(expectedProjection = it)
                }

            } else {

                try {
                    newReader?.close()
                } catch (_: Exception) {
                }

                try {
                    newProjection?.stop()
                } catch (_: Exception) {
                }

                worker.quitSafely()
            }

            throw e
        }
    }

    /*
     * Membersihkan seluruh sumber daya secara teratur.
     * expectedProjection mencegah callback lama
     * menghentikan sesi capture yang baru.
     */
    private fun stopProjection(
        expectedProjection: MediaProjection? = null,
        stopProjectionToken: Boolean = true
    ) {

        var oldDisplay: VirtualDisplay? = null
        var oldReader: ImageReader? = null
        var oldProjection: MediaProjection? = null
        var oldThread: HandlerThread? = null
        var oldCallback: MediaProjection.Callback? = null

        var shouldCleanup = true

        synchronized(lifecycleLock) {

            if (
                expectedProjection != null &&
                mediaProjection !== expectedProjection
            ) {
                shouldCleanup = false

            } else {

                oldDisplay = virtualDisplay
                oldReader = imageReader
                oldProjection = mediaProjection
                oldThread = handlerThread
                oldCallback = projectionCallback

                virtualDisplay = null
                mediaProjection = null
                handlerThread = null
                bgHandler = null
                projectionCallback = null

                synchronized(frameLock) {

                    imageReader = null

                    try {
                        latestImage?.close()
                    } catch (_: Exception) {
                    }

                    latestImage = null
                    lastFrameReceivedNs = 0L
                }
            }
        }

        if (!shouldCleanup) return

        try {
            oldDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            oldReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            oldReader?.close()
        } catch (_: Exception) {
        }

        try {
            if (oldCallback != null) {
                oldProjection?.unregisterCallback(oldCallback)
            }
        } catch (_: Exception) {
        }

        if (stopProjectionToken) {
            try {
                oldProjection?.stop()
            } catch (_: Exception) {
            }
        }

        try {
            oldThread?.quitSafely()
        } catch (_: Exception) {
        }

        Log.i(TAG, "Screen capture dihentikan")
    }

    private fun startForegroundServiceNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "FastAuto Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FastAuto Color Engine")
                .setContentText("Mesin pembaca layar sedang aktif")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
        )
    }

    override fun onDestroy() {

        stopProjection()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
