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
        @Volatile var screenDensity = 320

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

        // Koordinat & warna real-time untuk Crosshair Picker di MainActivity
        @Volatile private var pickerX = 540
        @Volatile private var pickerY = 1200
        @Volatile var cachedPickerRgb: Int = 0

        // Snapshot Raw Frame di RAM (Zero-Allocation Direct Buffer)
        // Memungkinkan baca warna Picker di layar statis (Samsung OneUI) & multi-blok Automate dalam 0 ms!
        private val snapshotLock = Object()
        private var snapshotBuffer: ByteBuffer? = null
        @Volatile private var snapWidth = 0
        @Volatile private var snapHeight = 0
        @Volatile private var snapRowStride = 0
        @Volatile private var snapPixelStride = 4
        @Volatile private var snapLimit = 0

        // Sinyal bangun instan untuk Mode Tunggu (Zero-CPU Wait)
        private val frameWaitLock = Object()

        // Buffer baris reusable agar 0% alokasi memori (Zero Garbage Collection)
        private var reusableRowBuffer = ByteArray(16384)
        private var evalRowBuffer = ByteArray(16384)

        // Idle Throttling (Tanpa mematikan VirtualDisplay surface agar 100% aman di Samsung Android 14/15)
        @Volatile private var lastActiveRequestTimeMs = 0L
        private var lastProcessedFrameTimeNs = 0L

        fun isReady(): Boolean = imageReader != null && mediaProjection != null

        fun markEngineActive() {
            lastActiveRequestTimeMs = System.currentTimeMillis()
        }

        fun updatePickerTarget(x: Int, y: Int) {
            pickerX = x
            pickerY = y
            markEngineActive()
            // Langsung baca dari Snapshot RAM detik itu juga meskipun layar HP sedang diam/statis!
            readPickerFromSnapshot(x, y)
        }

        private fun readPickerFromSnapshot(x: Int, y: Int): Boolean {
            synchronized(snapshotLock) {
                val buf = snapshotBuffer ?: return false
                val w = snapWidth
                val h = snapHeight
                val rs = snapRowStride
                val ps = snapPixelStride
                val lim = snapLimit
                if (w <= 0 || h <= 0 || lim <= 0) return false

                val px = x.coerceIn(0, w - 1)
                val py = y.coerceIn(0, h - 1)
                val pOff = py * rs + px * ps
                if (pOff >= 0 && pOff + 2 < lim) {
                    val r = buf.get(pOff).toInt() and 0xFF
                    val g = buf.get(pOff + 1).toInt() and 0xFF
                    val b = buf.get(pOff + 2).toInt() and 0xFF
                    cachedPickerRgb = (r shl 16) or (g shl 8) or b
                    return true
                }
                return false
            }
        }

        fun registerWatchConfig(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int
        ): Boolean {
            val rad = radius.coerceIn(1, 500)
            val stp = step.coerceIn(1, 20)
            val tol = tolerance.coerceIn(0, 255)

            val changed = (watchX1 != x1 || watchY1 != y1 || watchRgb1 != rgb1 ||
                    watchUseColor2 != useColor2 || watchLogic != colorLogic ||
                    watchX2 != x2 || watchY2 != y2 || watchRgb2 != rgb2 ||
                    watchRadius != rad || watchStep != stp || watchTol != tol)

            watchX1 = x1
            watchY1 = y1
            watchRgb1 = rgb1
            watchUseColor2 = useColor2
            watchLogic = colorLogic
            watchX2 = x2
            watchY2 = y2
            watchRgb2 = rgb2
            watchRadius = rad
            watchStep = stp
            watchTol = tol

            markEngineActive()

            if (changed) {
                // Evaluasi langsung pada Snapshot RAM saat itu juga agar tidak perlu tunggu frame baru!
                synchronized(snapshotLock) {
                    val buf = snapshotBuffer
                    if (buf != null && snapWidth > 0 && snapHeight > 0 && snapLimit > 0) {
                        cachedMatchResult = evaluateOnBufferLocked(
                            buf, snapWidth, snapHeight, snapRowStride, snapPixelStride, snapLimit,
                            x1, y1, rgb1, useColor2, colorLogic, x2, y2, rgb2, rad, stp, tol,
                            evalRowBuffer
                        )
                    }
                }
            }
            return changed
        }

        fun evaluateAreaInstantOrWait(
            x1: Int, y1: Int, rgb1: Int,
            useColor2: Boolean, colorLogic: Int,
            x2: Int, y2: Int, rgb2: Int,
            radius: Int, step: Int, tolerance: Int,
            timeoutMs: Long
        ): Boolean {
            registerWatchConfig(
                x1, y1, rgb1,
                useColor2, colorLogic,
                x2, y2, rgb2,
                radius, step, tolerance
            )

            // JALUR SUPER INSTAN (< 0.005 ms): Hasil sudah terhitung di RAM (baik dari pre-cache maupun snapshot)
            if (cachedMatchResult || timeoutMs <= 0L) {
                return cachedMatchResult
            }

            // JALUR TUNGGU FRAME-SYNC (In-Block Wait tanpa Loop di Automate)
            val waitBudget = timeoutMs.coerceIn(1L, 8000L)
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

        private fun processIncomingFrame(reader: ImageReader) {
            val img = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return

            // Wajib tutup Image di blok finally dalam mikrodetik yang sama agar GPU 0% Lag!
            try {
                val nowNs = System.nanoTime()
                val nowMs = System.currentTimeMillis()
                val isIdle = !isPickerOverlayOpen && (nowMs - lastActiveRequestTimeMs > 3500L)

                // Throttling cerdas:
                // - Saat Idle: proses 1 frame tiap 250ms (4 FPS) -> Beban CPU 0.0%, HP dingin, tanpa putus surface OneUI
                // - Saat Aktif: batasi celah minimal 8ms (~120 FPS max) agar tidak membanjiri CPU saat animasi berat
                val minIntervalNs = if (isIdle) 250_000_000L else 8_000_000L
                if (nowNs - lastProcessedFrameTimeNs < minIntervalNs) {
                    return
                }
                lastProcessedFrameTimeNs = nowNs

                val planes = img.planes
                if (planes.isNullOrEmpty()) return
                val plane = planes[0]
                val buffer = plane.buffer ?: return
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = img.width
                val height = img.height
                val limit = buffer.limit()

                if (width <= 0 || height <= 0 || limit <= 0) return

                // Sinkronisasi resolusi riil buffer (Penting untuk HP HD+ 720p seperti Samsung A07)
                if (screenWidth != width || screenHeight != height) {
                    screenWidth = width
                    screenHeight = height
                }

                // 1. Simpan ke Direct Snapshot Buffer (Copy memori C++ native super cepat ~0.1ms)
                synchronized(snapshotLock) {
                    var snap = snapshotBuffer
                    if (snap == null || snap.capacity() < limit) {
                        snap = ByteBuffer.allocateDirect(limit + 4096)
                        snapshotBuffer = snap
                    }
                    snap.clear()
                    buffer.position(0)
                    snap.put(buffer)
                    snap.flip()

                    snapWidth = width
                    snapHeight = height
                    snapRowStride = rowStride
                    snapPixelStride = pixelStride
                    snapLimit = limit
                }

                // 2. Update warna untuk Crosshair Picker bila sedang dibuka
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

                // 3. Pindai Area 1 & Area 2 dengan algoritma Center-Outward (Titik Tengah Melebar ke Luar)
                val finalMatch = evaluateOnBufferLocked(
                    buffer, width, height, rowStride, pixelStride, limit,
                    watchX1, watchY1, watchRgb1,
                    watchUseColor2, watchLogic,
                    watchX2, watchY2, watchRgb2,
                    watchRadius, watchStep, watchTol,
                    reusableRowBuffer
                )

                cachedMatchResult = finalMatch
                lastFrameTimestampNs = nowNs

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

        private fun evaluateOnBufferLocked(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            limit: Int,
            x1: Int, y1: Int, rgb1: Int,
            use2: Boolean, logic: Int,
            x2: Int, y2: Int, rgb2: Int,
            rad: Int, stp: Int, tol: Int,
            rowWorkspace: ByteArray
        ): Boolean {
            val m1 = scanBufferAreaCenterOutward(
                buffer, width, height, rowStride, pixelStride, limit,
                x1, y1, rad, stp, rgb1, tol, rowWorkspace
            )
            return if (!use2) {
                m1
            } else if (logic == 0) {
                if (!m1) false else scanBufferAreaCenterOutward(
                    buffer, width, height, rowStride, pixelStride, limit,
                    x2, y2, rad, stp, rgb2, tol, rowWorkspace
                )
            } else {
                if (m1) true else scanBufferAreaCenterOutward(
                    buffer, width, height, rowStride, pixelStride, limit,
                    x2, y2, rad, stp, rgb2, tol, rowWorkspace
                )
            }
        }

        /**
         * Algoritma Center-Outward Bulk Scan:
         * Memeriksa titik pusat (cx, cy) terlebih dahulu, lalu memeriksa baris tengah (cy)
         * dan melebar bergantian ke atas (cy - dy) & bawah (cy + dy).
         * 5x lebih cepat menemukan tombol/elemen UI yang bergeser sedikit dibanding scan dari kiri-atas!
         */
        private fun scanBufferAreaCenterOutward(
            buffer: ByteBuffer,
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
            tolerance: Int,
            initialRowBuf: ByteArray
        ): Boolean {
            val r2 = (targetRgb shr 16) and 0xFF
            val g2 = (targetRgb shr 8) and 0xFF
            val b2 = targetRgb and 0xFF

            val cx = centerX.coerceIn(0, width - 1)
            val cy = centerY.coerceIn(0, height - 1)

            // 1. Cek titik pusat persis terlebih dahulu (0.0005 ms Fastest Hit!)
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
            val minY = (cy - radius).coerceAtLeast(0)
            val maxY = (cy + radius).coerceAtMost(height - 1)

            val bytesNeeded = (endX - startX + 1) * pixelStride
            if (bytesNeeded <= 0) return false

            val rowArr = if (initialRowBuf.size >= bytesNeeded) {
                initialRowBuf
            } else {
                val expanded = ByteArray(bytesNeeded + 1024)
                if (initialRowBuf === reusableRowBuffer) reusableRowBuffer = expanded else evalRowBuffer = expanded
                expanded
            }

            val maxLocalIdx = bytesNeeded - 3
            val strideStep = step * pixelStride

            fun checkSingleRow(y: Int): Boolean {
                val rowStartOff = y * rowStride + startX * pixelStride
                if (rowStartOff < 0 || rowStartOff + bytesNeeded > limit) return false

                buffer.position(rowStartOff)
                buffer.get(rowArr, 0, bytesNeeded)

                var localIdx = 0
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
                return false
            }

            // 2. Cek baris pusat (cy)
            if (checkSingleRow(cy)) return true

            // 3. Melebar ke atas (cy - offset) dan bawah (cy + offset) secara bergantian
            var offset = step
            while (cy - offset >= minY || cy + offset <= maxY) {
                val yUp = cy - offset
                if (yUp >= minY && checkSingleRow(yUp)) return true

                val yDown = cy + offset
                if (yDown <= maxY && checkSingleRow(yDown)) return true

                offset += step
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

        val realW = metrics.widthPixels.coerceAtLeast(1)
        val realH = metrics.heightPixels.coerceAtLeast(1)
        screenWidth = realW
        screenHeight = realH
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
        val reader = ImageReader.newInstance(realW, realH, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            processIncomingFrame(r)
        }, handler)
        imageReader = reader

        lastActiveRequestTimeMs = System.currentTimeMillis()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FastAutoColorEngine",
            realW, realH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun stopProjection() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        try { bgThread?.quitSafely() } catch (_: Throwable) {}
        bgThread = null
        bgHandler = null
        synchronized(snapshotLock) {
            snapshotBuffer = null
            snapWidth = 0
            snapHeight = 0
            snapLimit = 0
        }
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
