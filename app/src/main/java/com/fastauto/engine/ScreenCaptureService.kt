package com.fastauto.engine

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenCaptureService : Service() {

    enum class EvalStatus {
        MATCHED,
        NOT_MATCHED,
        ERR_ENGINE_NOT_READY,
        ERR_CAPTURE_FAILED,
        ERR_INVALID_CONFIG
    }

    data class EvalOutcome(
        val status: EvalStatus,
        val matchedCount: Int = 0,
        val detail: String = ""
    )

    companion object {
        const val ACTION_STOP_SERVICE = "com.fastauto.engine.action.STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "com.fastauto.engine.extra.RESULT_CODE"
        const val EXTRA_DATA = "com.fastauto.engine.extra.DATA"

        const val COLOR_MODE_DUAL_SIMULTANEOUS = 1
        const val COLOR_MODE_SINGLE = 2
        const val COLOR_MODE_PICK_ONE = 3

        const val PICK_ONE_COLOR_1 = 1
        const val PICK_ONE_COLOR_2 = 2

        const val LOGIC_AND = 0
        const val LOGIC_OR = 1

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fastauto_capture_channel"
        private const val SINGLE_FRAME_WAIT_MS = 120L

        @Volatile var screenWidth: Int = 0
        @Volatile var screenHeight: Int = 0
        @Volatile var screenDensity: Int = 0
        @Volatile var screenRotation: Int = Surface.ROTATION_0

        private val stateLock = Object()
        private val frameLock = Object()

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var latestPickerImage: Image? = null

        private var frameThread: HandlerThread? = null
        private var frameHandler: Handler? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        private var activeReadersCount: Int = 0
        private var pickerActiveFlag: Boolean = false
        private var pickerHoldsReader: Boolean = false
        private var frameCounter: Long = 0L

        private val frameAvailableListener = ImageReader.OnImageAvailableListener {
            synchronized(frameLock) {
                frameCounter++
                frameLock.notifyAll()
            }
        }

        fun isReady(): Boolean {
            synchronized(stateLock) {
                return mediaProjection != null && virtualDisplay != null && screenWidth > 0 && screenHeight > 0
            }
        }

        fun setPickerActive(active: Boolean) {
            synchronized(stateLock) {
                pickerActiveFlag = active
                if (active) {
                    if (!pickerHoldsReader && mediaProjection != null && virtualDisplay != null) {
                        if (attachReaderLocked(maxImages = 3) != null) {
                            activeReadersCount++
                            pickerHoldsReader = true
                        }
                    }
                } else {
                    if (pickerHoldsReader) {
                        pickerHoldsReader = false
                        closePickerImageLocked()
                        releaseReaderLocked()
                    }
                }
            }
        }

        fun evaluateOneShot(
            colorMode: Int,
            pickOneTarget: Int,
            colorLogic: Int,
            x1: Int, y1: Int, rgb1: Int,
            x2: Int, y2: Int, rgb2: Int,
            refWidth: Int, refHeight: Int, refRotation: Int,
            radius: Int, step: Int, tolerance: Int, minMatches: Int
        ): EvalOutcome {
            val activeMode = when (colorMode) {
                COLOR_MODE_DUAL_SIMULTANEOUS -> COLOR_MODE_DUAL_SIMULTANEOUS
                COLOR_MODE_SINGLE -> COLOR_MODE_SINGLE
                COLOR_MODE_PICK_ONE -> COLOR_MODE_PICK_ONE
                else -> return EvalOutcome(EvalStatus.ERR_INVALID_CONFIG, 0, "INVALID_COLOR_MODE")
            }

            val useColor1: Boolean
            val useColor2: Boolean
            when (activeMode) {
                COLOR_MODE_SINGLE -> {
                    useColor1 = true
                    useColor2 = false
                }
                COLOR_MODE_PICK_ONE -> {
                    if (pickOneTarget == PICK_ONE_COLOR_2) {
                        useColor1 = false
                        useColor2 = true
                    } else {
                        useColor1 = true
                        useColor2 = false
                    }
                }
                COLOR_MODE_DUAL_SIMULTANEOUS -> {
                    useColor1 = true
                    useColor2 = true
                }
                else -> return EvalOutcome(EvalStatus.ERR_INVALID_CONFIG, 0, "INVALID_COLOR_MODE")
            }

            val rad = radius.coerceIn(1, 600)
            val stp = step.coerceIn(1, 24)
            val tol = tolerance.coerceIn(0, 255)
            val reqMatches = minMatches.coerceIn(1, 500)

            var acquiredHere = false
            var prevFrameSeq = 0L
            val curRot: Int

            synchronized(stateLock) {
                if (mediaProjection == null || virtualDisplay == null || screenWidth <= 0 || screenHeight <= 0) {
                    return EvalOutcome(EvalStatus.ERR_ENGINE_NOT_READY, 0, "ENGINE_NOT_READY")
                }
                curRot = screenRotation
                prevFrameSeq = synchronized(frameLock) { frameCounter }

                if (attachReaderLocked(maxImages = if (pickerHoldsReader) 3 else 2) == null) {
                    return EvalOutcome(EvalStatus.ERR_CAPTURE_FAILED, 0, "READER_ATTACH_FAILED")
                }
                activeReadersCount++
                acquiredHere = true
            }

            try {
                val deadlineNanos = System.nanoTime() + (SINGLE_FRAME_WAIT_MS * 1_000_000L)
                var image: Image? = acquireFreshImageLocked()

                while (image == null) {
                    val remNanos = deadlineNanos - System.nanoTime()
                    if (remNanos <= 0L) break

                    val waitMs = ((remNanos + 999_999L) / 1_000_000L).coerceIn(1L, 30L)
                    synchronized(frameLock) {
                        if (frameCounter == prevFrameSeq) {
                            try {
                                frameLock.wait(waitMs)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        prevFrameSeq = frameCounter
                    }
                    image = acquireFreshImageLocked()
                }

                if (image == null) {
                    return EvalOutcome(EvalStatus.ERR_CAPTURE_FAILED, 0, "NO_FRESH_FRAME")
                }

                return try {
                    val imgW = image.width
                    val imgH = image.height
                    if (imgW <= 0 || imgH <= 0) {
                        EvalOutcome(EvalStatus.ERR_CAPTURE_FAILED, 0, "INVALID_IMAGE_SIZE")
                    } else {
                        val mapped1 = mapCoordinateToCurrentBuffer(
                            x1, y1, refWidth, refHeight, refRotation,
                            imgW, imgH, curRot
                        )
                        val mapped2 = mapCoordinateToCurrentBuffer(
                            x2, y2, refWidth, refHeight, refRotation,
                            imgW, imgH, curRot
                        )
                        val mappedRad = mapRadiusToCurrentBuffer(
                            rad, refWidth, refHeight, imgW, imgH
                        )

                        evaluateRoiOnce(
                            image = image,
                            useColor1 = useColor1,
                            useColor2 = useColor2,
                            colorLogic = colorLogic,
                            cx1 = mapped1.first, cy1 = mapped1.second, rgb1 = rgb1,
                            cx2 = mapped2.first, cy2 = mapped2.second, rgb2 = rgb2,
                            radius = mappedRad,
                            step = stp,
                            tolerance = tol,
                            minMatches = reqMatches
                        )
                    }
                } finally {
                    try { image.close() } catch (_: Throwable) {}
                }
            } finally {
                if (acquiredHere) {
                    synchronized(stateLock) {
                        releaseReaderLocked()
                    }
                }
            }
        }

        fun getPixelHex(x: Int, y: Int): String? {
            var tempReader = false
            var prevSeq = 0L

            synchronized(stateLock) {
                if (mediaProjection == null || virtualDisplay == null || screenWidth <= 0 || screenHeight <= 0) {
                    return null
                }
                prevSeq = synchronized(frameLock) { frameCounter }
                if (imageReader == null) {
                    if (attachReaderLocked(maxImages = if (pickerHoldsReader) 3 else 2) == null) {
                        return null
                    }
                    activeReadersCount++
                    tempReader = true
                }
            }

            try {
                val deadlineNanos = System.nanoTime() + (SINGLE_FRAME_WAIT_MS * 1_000_000L)
                var img: Image? = acquireFreshImageLocked()

                while (img == null && pickerHoldsReader && latestPickerImage != null) {
                    val rem = deadlineNanos - System.nanoTime()
                    if (rem <= (SINGLE_FRAME_WAIT_MS * 500_000L)) {
                        img = latestPickerImage
                    } else {
                        synchronized(frameLock) {
                            if (frameCounter == prevSeq) {
                                try {
                                    frameLock.wait(20L)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                            }
                            prevSeq = frameCounter
                        }
                        img = acquireFreshImageLocked()
                    }
                    if (img != null) break
                }

                while (img == null) {
                    val rem = deadlineNanos - System.nanoTime()
                    if (rem <= 0L) break
                    synchronized(frameLock) {
                        if (frameCounter == prevSeq) {
                            try {
                                frameLock.wait(25L)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        prevSeq = frameCounter
                    }
                    img = acquireFreshImageLocked()
                }

                val target = img ?: return null
                val shouldClose = !(pickerHoldsReader && target === latestPickerImage)
                return try {
                    readRgbHexFromImage(target, x, y)
                } finally {
                    if (shouldClose) {
                        try { target.close() } catch (_: Throwable) {}
                    }
                }
            } finally {
                if (tempReader) {
                    synchronized(stateLock) {
                        releaseReaderLocked()
                    }
                }
            }
        }

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

        fun mapCoordinateToCurrentBuffer(
            x: Int,
            y: Int,
            refW: Int,
            refH: Int,
            refRot: Int,
            bufW: Int,
            bufH: Int,
            bufRot: Int
        ): Pair<Int, Int> {
            val safeX = x.coerceAtLeast(0)
            val safeY = y.coerceAtLeast(0)
            if (bufW <= 0 || bufH <= 0) return Pair(0, 0)

            val srcW = if (refW > 0) refW else bufW
            val srcH = if (refH > 0) refH else bufH

            val rotDiff = ((bufRot - refRot) % 4 + 4) % 4
            val mappedX: Int
            val mappedY: Int

            when (rotDiff) {
                1 -> {
                    mappedX = (((srcH - 1 - safeY).coerceAtLeast(0).toLong() * bufW) / srcH).toInt()
                    mappedY = ((safeX.toLong() * bufH) / srcW).toInt()
                }
                3 -> {
                    mappedX = ((safeY.toLong() * bufW) / srcH).toInt()
                    mappedY = (((srcW - 1 - safeX).coerceAtLeast(0).toLong() * bufH) / srcW).toInt()
                }
                2 -> {
                    mappedX = (((srcW - 1 - safeX).coerceAtLeast(0).toLong() * bufW) / srcW).toInt()
                    mappedY = (((srcH - 1 - safeY).coerceAtLeast(0).toLong() * bufH) / srcH).toInt()
                }
                else -> {
                    mappedX = ((safeX.toLong() * bufW) / srcW).toInt()
                    mappedY = ((safeY.toLong() * bufH) / srcH).toInt()
                }
            }

            return Pair(
                mappedX.coerceIn(0, bufW - 1),
                mappedY.coerceIn(0, bufH - 1)
            )
        }

        private fun mapRadiusToCurrentBuffer(
            radius: Int,
            refW: Int,
            refH: Int,
            bufW: Int,
            bufH: Int
        ): Int {
            val srcMin = minOf(if (refW > 0) refW else bufW, if (refH > 0) refH else bufH).coerceAtLeast(1)
            val dstMin = minOf(bufW, bufH).coerceAtLeast(1)
            return ((radius.toLong() * dstMin) / srcMin).toInt().coerceIn(1, 600)
        }

        private fun acquireFreshImageLocked(): Image? {
            val reader = synchronized(stateLock) { imageReader } ?: return null
            val fresh = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            }
            if (fresh != null) {
                synchronized(stateLock) {
                    if (pickerHoldsReader) {
                        closePickerImageLocked()
                        latestPickerImage = fresh
                    }
                }
            }
            return fresh
        }

        private fun readRgbHexFromImage(img: Image, x: Int, y: Int): String? {
            return try {
                val planes = img.planes
                if (planes.isEmpty()) return null
                val plane = planes[0]
                val buf = plane.buffer
                val w = img.width
                val h = img.height
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
            }
        }

        private fun evaluateRoiOnce(
            image: Image,
            useColor1: Boolean,
            useColor2: Boolean,
            colorLogic: Int,
            cx1: Int, cy1: Int, rgb1: Int,
            cx2: Int, cy2: Int, rgb2: Int,
            radius: Int,
            step: Int,
            tolerance: Int,
            minMatches: Int
        ): EvalOutcome {
            val planes = try { image.planes } catch (_: Throwable) { null }
            if (planes == null || planes.isEmpty()) {
                return EvalOutcome(EvalStatus.ERR_CAPTURE_FAILED, 0, "EMPTY_PLANES")
            }
            val plane = planes[0]
            val buf = plane.buffer

            val w = image.width
            val h = image.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            if (w <= 0 || h <= 0 || rs <= 0 || ps <= 0) {
                return EvalOutcome(EvalStatus.ERR_CAPTURE_FAILED, 0, "INVALID_STRIDE")
            }

            buf.order(ByteOrder.LITTLE_ENDIAN)
            val limit = buf.limit()

            val count1 = if (useColor1) {
                countMatchesInBoxRowMajor(buf, limit, w, h, rs, ps, cx1, cy1, radius, step, rgb1, tolerance, minMatches)
            } else {
                0
            }

            val count2 = if (useColor2) {
                countMatchesInBoxRowMajor(buf, limit, w, h, rs, ps, cx2, cy2, radius, step, rgb2, tolerance, minMatches)
            } else {
                0
            }

            val matched = when {
                useColor1 && useColor2 -> {
                    if (colorLogic == LOGIC_OR) {
                        count1 >= minMatches || count2 >= minMatches
                    } else {
                        count1 >= minMatches && count2 >= minMatches
                    }
                }
                useColor1 -> count1 >= minMatches
                useColor2 -> count2 >= minMatches
                else -> false
            }

            val totalMatched = when {
                useColor1 && useColor2 -> if (colorLogic == LOGIC_OR) maxOf(count1, count2) else minOf(count1, count2)
                useColor1 -> count1
                else -> count2
            }

            return if (matched) {
                EvalOutcome(EvalStatus.MATCHED, totalMatched, "MATCHED")
            } else {
                EvalOutcome(EvalStatus.NOT_MATCHED, totalMatched, "NOT_MATCHED")
            }
        }

        private fun countMatchesInBoxRowMajor(
            buf: ByteBuffer,
            limit: Int,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetRgb: Int,
            tolerance: Int,
            minMatches: Int
        ): Int {
            val tr = (targetRgb ushr 16) and 0xFF
            val tg = (targetRgb ushr 8) and 0xFF
            val tb = targetRgb and 0xFF

            val rMin = tr - tolerance
            val rMax = tr + tolerance
            val gMin = tg - tolerance
            val gMax = tg + tolerance
            val bMin = tb - tolerance
            val bMax = tb + tolerance

            val left = (centerX - radius).coerceIn(0, width - 1)
            val right = (centerX + radius).coerceIn(0, width - 1)
            val top = (centerY - radius).coerceIn(0, height - 1)
            val bottom = (centerY + radius).coerceIn(0, height - 1)

            val maxChannelDistSq = tolerance * tolerance * 3

            var matches = 0
            var y = top
            while (y <= bottom) {
                val rowBase = y * rowStride
                var x = left
                while (x <= right) {
                    val off = rowBase + x * pixelStride
                    if (off >= 0 && off + 2 < limit) {
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

                        if (r in rMin..rMax && g in gMin..gMax && b in bMin..bMax) {
                            val dr = r - tr
                            val dg = g - tg
                            val db = b - tb
                            if ((dr * dr + dg * dg + db * db) <= maxChannelDistSq) {
                                matches++
                                if (matches >= minMatches) {
                                    return matches
                                }
                            }
                        }
                    }
                    x += step
                }
                if (y == bottom) {
                    y = bottom + 1
                } else {
                    val nextY = y + step
                    y = if (nextY > bottom) bottom else nextY
                }
            }
            return matches
        }

        private fun attachReaderLocked(maxImages: Int): ImageReader? {
            val vd = virtualDisplay ?: return null
            if (mediaProjection == null) return null
            val w = screenWidth
            val h = screenHeight
            if (w <= 0 || h <= 0) return null

            var reader = imageReader
            if (reader == null) {
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, maxImages)
                reader.setOnImageAvailableListener(frameAvailableListener, frameHandler)
                imageReader = reader
                try {
                    vd.setSurface(reader.surface)
                } catch (_: Throwable) {
                    imageReader = null
                    try { reader.close() } catch (_: Throwable) {}
                    return null
                }
            }
            return reader
        }

        private fun releaseReaderLocked() {
            if (activeReadersCount > 0) {
                activeReadersCount--
            }
            if (activeReadersCount <= 0 && !pickerHoldsReader) {
                activeReadersCount = 0
                detachAndCloseReaderLocked()
            }
        }

        private fun closePickerImageLocked() {
            val img = latestPickerImage
            latestPickerImage = null
            if (img != null) {
                try { img.close() } catch (_: Throwable) {}
            }
        }

        private fun detachAndCloseReaderLocked() {
            closePickerImageLocked()
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
                syncDisplayMetrics()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
                startForegroundNotification()
                val ok = startProjection(resultCode, data)
                if (!ok) {
                    shutdownServiceCompletely()
                }
            } catch (_: Throwable) {
                shutdownServiceCompletely()
            }
        } else if (!isReady()) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncDisplayMetrics()
    }

    private fun readDisplayMetrics(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.configuration.densityDpi.coerceAtLeast(120)
        @Suppress("DEPRECATION")
        val rotation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: wm.defaultDisplay.rotation
            } else {
                wm.defaultDisplay.rotation
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }

        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        }

        return Pair(size, Pair(densityDpi, rotation))
    }

    private fun syncDisplayMetrics() {
        val (size, meta) = readDisplayMetrics()
        val (w, h) = size
        val (dpi, rot) = meta
        applyResize(w, h, dpi, rot)
    }

    private fun applyResize(newW: Int, newH: Int, newDpi: Int, newRot: Int) {
        if (newW <= 0 || newH <= 0) return
        synchronized(stateLock) {
            val vd = virtualDisplay ?: return
            if (newW == screenWidth && newH == screenHeight && newDpi == screenDensity && newRot == screenRotation) {
                return
            }
            screenWidth = newW
            screenHeight = newH
            screenDensity = newDpi
            screenRotation = newRot

            val hadReader = (imageReader != null)
            val prevCount = activeReadersCount
            if (hadReader) {
                detachAndCloseReaderLocked()
            }
            try {
                vd.resize(newW, newH, newDpi)
            } catch (_: Throwable) {}

            if (hadReader && (pickerHoldsReader || prevCount > 0)) {
                if (attachReaderLocked(maxImages = if (pickerHoldsReader) 3 else 2) != null) {
                    activeReadersCount = maxOf(prevCount, if (pickerHoldsReader) 1 else 0)
                }
            }
        }
    }

    private fun startProjection(resultCode: Int, data: Intent): Boolean {
        stopProjection()

        val (size, meta) = readDisplayMetrics()
        val (w, h) = size
        val (dpi, rot) = meta

        val thread = HandlerThread("FastColorFrameThread", Process.THREAD_PRIORITY_DISPLAY)
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
                val currentDpi = resources.configuration.densityDpi.coerceAtLeast(120)
                val currentRot = screenRotation
                applyResize(width, height, currentDpi, currentRot)
            }
        }, handler)

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

        synchronized(stateLock) {
            screenWidth = w
            screenHeight = h
            screenDensity = dpi
            screenRotation = rot
            frameThread = thread
            frameHandler = handler
            mediaProjection = projection
            virtualDisplay = vd

            if (pickerActiveFlag && !pickerHoldsReader) {
                if (attachReaderLocked(maxImages = 3) != null) {
                    activeReadersCount++
                    pickerHoldsReader = true
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

        synchronized(stateLock) {
            activeReadersCount = 0
            pickerHoldsReader = false
            detachAndCloseReaderLocked()

            vdToRelease = virtualDisplay
            virtualDisplay = null
            mpToStop = mediaProjection
            mediaProjection = null
            threadToQuit = frameThread
            frameThread = null
            frameHandler = null
        }

        synchronized(frameLock) {
            frameLock.notifyAll()
        }

        try { vdToRelease?.release() } catch (_: Throwable) {}
        try { mpToStop?.stop() } catch (_: Throwable) {}
        try { threadToQuit?.quitSafely() } catch (_: Throwable) {}
    }

    private fun startForegroundNotification() {
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
            .setContentText("One-Shot Area Color Scanner (True-Idle)")
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
