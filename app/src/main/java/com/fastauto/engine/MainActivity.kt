package com.fastauto.engine

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 1001
        private const val REQ_OVERLAY_PERMISSION = 1002
        private const val REQ_POST_NOTIFICATIONS = 1003

        private const val PREFS_NAME = "fast_auto_prefs"
        private const val SAMPLE_THROTTLE_MS = 60L
        private const val DELAYED_SAMPLE_MS = 45L
    }

    private lateinit var statusText: TextView
    private var floatingView: View? = null
    private var crosshairView: View? = null
    private var dpadView: View? = null

    private var lastPickedX = 360
    private var lastPickedY = 800
    private var lastPickedHex = "#FF0000"

    private val uiHandler = Handler(Looper.getMainLooper())
    private val pickerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FastPickerSampleThread").apply { isDaemon = true }
    }
    private val isSamplingInFlight = AtomicBoolean(false)
    private var lastSampleUptimeMs = 0L

    private var repeatRunnable: Runnable? = null
    private var delayedSampleRunnable: Runnable? = null

    private var infoTvRef: TextView? = null
    private var hexTvRef: TextView? = null
    private var colorPreviewDrawable: GradientDrawable? = null
    private var cParamsRef: WindowManager.LayoutParams? = null

    private var statusOffsetX = 0
    private var statusOffsetY = 0

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun crosshairSizePx(): Int = dp(48).coerceAtLeast(36)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (w, h) = getExactScreenSize()
        lastPickedX = prefs.getInt("picked_x", w / 2)
        lastPickedY = prefs.getInt("picked_y", h / 2)
        lastPickedHex = ScreenCaptureService.parseHexToIntOrNull(prefs.getString("picked_hex", "#FF0000"))
            ?.let { String.format("#%06X", it) } ?: "#FF0000"

        setupUI()
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun getExactScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width().coerceAtLeast(1), b.height().coerceAtLeast(1))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        }
    }

    private fun applyCutoutMode(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupUI() {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                dp(16) + sysBars.left,
                dp(16) + sysBars.top,
                dp(16) + sysBars.right,
                dp(16) + sysBars.bottom
            )
            insets
        }

        val title = TextView(this).apply {
            text = "⚡ FastAuto Area Color Plugin"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(4))
        }
        mainLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Mesin Pemindai Area One-Shot untuk Automate.\n\nPENTING: Tutup alat bidik (klik ✕) sebelum menjalankan flow Automate agar overlay tidak menghalangi layar dan sesi kembali True-Idle."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        }
        mainLayout.addView(subtitle)

        statusText = TextView(this).apply {
            text = "Status Mesin: Memeriksa..."
            textSize = 15f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, dp(14))
        }
        mainLayout.addView(statusText)

        mainLayout.addView(createButton("1. Aktifkan Mesin Area RAM (Wajib)", "#8E24AA") {
            launchScreenCaptureConsent()
        })

        mainLayout.addView(createButton("2. Munculkan Alat Bidik Pusat Area (🎯)", "#00897B") {
            if (!hasOverlayPermission()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_OVERLAY_PERMISSION)
            } else {
                showFloatingPicker()
            }
        })

        mainLayout.addView(createButton("3. 🛑 Matikan Mesin & Hapus Notifikasi", "#C62828") {
            removeAllOverlays()
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(stopIntent)
                } catch (_: Throwable) {
                    stopService(stopIntent)
                }
            } else {
                stopService(stopIntent)
            }
            statusText.postDelayed({ updateStatus() }, 200)
            Toast.makeText(this, "Mesin pembaca warna dimatikan (True-Idle)", Toast.LENGTH_SHORT).show()
        })

        setContentView(mainLayout)
        updateStatus()
    }

    private fun launchScreenCaptureConsent() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val intent = mpManager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (floatingView != null || crosshairView != null || dpadView != null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (maxW, maxH) = getExactScreenSize()
            val sizePx = crosshairSizePx()
            cParamsRef?.let { cp ->
                cp.x = cp.x.coerceIn(0, (maxW - sizePx).coerceAtLeast(0))
                cp.y = cp.y.coerceIn(0, (maxH - sizePx).coerceAtLeast(0))
                crosshairView?.let { cv ->
                    try { wm.updateViewLayout(cv, cp) } catch (_: Throwable) {}
                }
            }
            syncAndRefreshFromCrosshair(forceSample = true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        removeAllOverlays()
        uiHandler.removeCallbacksAndMessages(null)
        pickerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun updateStatus() {
        val colorOk = ScreenCaptureService.isReady()
        statusText.text = if (colorOk) {
            "Mesin Area RAM: ✅ AKTIF (One-Shot, True-Idle saat tidak dipanggil)"
        } else {
            "Mesin Area RAM: ❌ MATI"
        }
    }

    private fun removeAllOverlays() {
        ScreenCaptureService.setPickerActive(false)
        stopRepeatMove()
        delayedSampleRunnable?.let { uiHandler.removeCallbacks(it) }
        delayedSampleRunnable = null

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        crosshairView?.let {
            it.setOnTouchListener(null)
            try { wm.removeView(it) } catch (_: Exception) {}
            crosshairView = null
        }
        floatingView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            floatingView = null
        }
        dpadView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            dpadView = null
        }
        cParamsRef = null
        infoTvRef = null
        hexTvRef = null
        colorPreviewDrawable = null
    }

    private fun syncAndRefreshFromCrosshair(forceSample: Boolean = true, onDone: (() -> Unit)? = null) {
        val target = crosshairView ?: run {
            onDone?.invoke()
            return
        }
        target.post {
            if (crosshairView !== target) {
                onDone?.invoke()
                return@post
            }
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)

            val cParams = cParamsRef
            if (cParams != null) {
                statusOffsetX = loc[0] - cParams.x
                statusOffsetY = loc[1] - cParams.y
            }

            val trueCenterX = loc[0] + (target.width / 2)
            val trueCenterY = loc[1] + (target.height / 2)
            updateCoordinatesAndSample(trueCenterX, trueCenterY, forceSample = forceSample, onDone = onDone)

            if (forceSample) {
                delayedSampleRunnable?.let { uiHandler.removeCallbacks(it) }
                delayedSampleRunnable = Runnable {
                    val curTarget = crosshairView ?: return@Runnable
                    val locAfter = IntArray(2)
                    curTarget.getLocationOnScreen(locAfter)
                    val cx = locAfter[0] + (curTarget.width / 2)
                    val cy = locAfter[1] + (curTarget.height / 2)
                    updateCoordinatesAndSample(cx, cy, forceSample = true)
                }
                uiHandler.postDelayed(delayedSampleRunnable!!, DELAYED_SAMPLE_MS)
            }
        }
    }

    private fun updateCoordinatesAndSample(
        rawX: Int,
        rawY: Int,
        forceSample: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        val (physW, physH) = getExactScreenSize()
        val bufW = if (ScreenCaptureService.screenWidth > 0) ScreenCaptureService.screenWidth else physW
        val bufH = if (ScreenCaptureService.screenHeight > 0) ScreenCaptureService.screenHeight else physH

        val calibratedX = ((rawX.toLong() * bufW) / physW).toInt().coerceIn(0, bufW - 1)
        val calibratedY = ((rawY.toLong() * bufH) / physH).toInt().coerceIn(0, bufH - 1)

        lastPickedX = calibratedX
        lastPickedY = calibratedY
        infoTvRef?.text = "X: $lastPickedX   Y: $lastPickedY"

        if (!ScreenCaptureService.isReady()) {
            hexTvRef?.text = "MESIN MATI"
            try { colorPreviewDrawable?.setColor(Color.parseColor("#333333")) } catch (_: Exception) {}
            onDone?.invoke()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!forceSample && (now - lastSampleUptimeMs < SAMPLE_THROTTLE_MS)) {
            onDone?.invoke()
            return
        }
        if (!isSamplingInFlight.compareAndSet(false, true)) {
            if (forceSample) {
                uiHandler.postDelayed({
                    updateCoordinatesAndSample(rawX, rawY, forceSample = true, onDone = onDone)
                }, 25L)
            } else {
                onDone?.invoke()
            }
            return
        }

        lastSampleUptimeMs = now
        val targetX = calibratedX
        val targetY = calibratedY

        pickerExecutor.execute {
            val hex = try {
                ScreenCaptureService.getPixelHex(targetX, targetY)
            } catch (_: Throwable) {
                null
            } finally {
                isSamplingInFlight.set(false)
            }

            uiHandler.post {
                if (floatingView == null) {
                    onDone?.invoke()
                    return@post
                }
                if (hex != null) {
                    lastPickedHex = hex
                    hexTvRef?.text = hex
                    try {
                        colorPreviewDrawable?.setColor(Color.parseColor(hex))
                    } catch (_: Exception) {}
                } else {
                    hexTvRef?.text = "N/A"
                }
                onDone?.invoke()
            }
        }
    }

    private fun moveCrosshairBy(dx: Int, dy: Int, finalStep: Boolean = false) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val target = crosshairView ?: return
        val cParams = cParamsRef ?: return

        val (maxW, maxH) = getExactScreenSize()
        val sizePx = crosshairSizePx()

        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val currentScreenX = loc[0] + (target.width / 2)
        val currentScreenY = loc[1] + (target.height / 2)

        val nextScreenX = (currentScreenX + dx).coerceIn(0, maxW - 1)
        val nextScreenY = (currentScreenY + dy).coerceIn(0, maxH - 1)

        cParams.x = nextScreenX - (sizePx / 2) - statusOffsetX
        cParams.y = nextScreenY - (sizePx / 2) - statusOffsetY

        try {
            wm.updateViewLayout(target, cParams)
        } catch (_: Exception) {}

        syncAndRefreshFromCrosshair(forceSample = finalStep)
    }

    private fun stopRepeatMove() {
        repeatRunnable?.let { uiHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun saveAreaSlot(slot: Int) {
        syncAndRefreshFromCrosshair(forceSample = true) {
            val xKey = if (slot == 1) "picked_x" else "picked_x2"
            val yKey = if (slot == 1) "picked_y" else "picked_y2"
            val hexKey = if (slot == 1) "picked_hex" else "picked_hex2"
            val labelPrefix = if (slot == 1) "Area 1" else "Area 2"
            val clipLabel = if (slot == 1) "color1" else "color2"

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(xKey, lastPickedX)
                .putInt(yKey, lastPickedY)
                .putString(hexKey, lastPickedHex)
                .apply()

            try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText(clipLabel, lastPickedHex))
            } catch (_: Throwable) {}

            Toast.makeText(
                this@MainActivity,
                "$labelPrefix Tersimpan: ($lastPickedX, $lastPickedY) $lastPickedHex",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showFloatingPicker() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (floatingView != null) return

        ScreenCaptureService.setPickerActive(true)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141414"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1).coerceAtLeast(2), Color.parseColor("#00E676"))
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val initialColor = try {
            Color.parseColor(lastPickedHex)
        } catch (_: Exception) {
            Color.RED
        }

        val swatchBg = GradientDrawable().apply {
            setColor(initialColor)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(2), Color.WHITE)
        }
        colorPreviewDrawable = swatchBg

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(0, 0, dp(8), 0)
            }
            background = swatchBg
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val infoTv = TextView(this).apply {
            text = "X: $lastPickedX   Y: $lastPickedY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }
        infoTvRef = infoTv

        val hexTv = TextView(this).apply {
            text = lastPickedHex
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.parseColor("#00E676"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }
        hexTvRef = hexTv

        textCol.addView(infoTv)
        textCol.addView(hexTv)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C62828"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                setMargins(dp(6), 0, 0, 0)
            }
            setOnClickListener { removeAllOverlays() }
        }

        topRow.addView(colorPreview)
        topRow.addView(textCol)
        topRow.addView(closeBtn)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, 0)
            }
        }

        val saveBtn1 = TextView(this).apply {
            text = "🎯 Area 1"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E88E5"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
            setOnClickListener { saveAreaSlot(1) }
        }

        val saveBtn2 = TextView(this).apply {
            text = "🎯 Area 2"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#8E24AA"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener { saveAreaSlot(2) }
        }

        btnRow.addView(saveBtn1)
        btnRow.addView(saveBtn2)

        panel.addView(topRow)
        panel.addView(btnRow)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(215),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(100)
            applyCutoutMode(this)
        }

        makeDraggable(panel, params, wm, refreshOnMove = false)

        wm.addView(panel, params)
        floatingView = panel

        showHollowCrosshair(wm)
        showDraggableJoystick(wm)
        syncAndRefreshFromCrosshair(forceSample = true)
    }

    private fun showDraggableJoystick(wm: WindowManager) {
        if (dpadView != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val dpadParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(240)
            y = dp(100)
            applyCutoutMode(this)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9181818"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1).coerceAtLeast(2), Color.parseColor("#00E676"))
            }
        }

        val btnSize = dp(32)
        val gap = dp(2)

        fun createDirBtn(symbol: String, dx: Int, dy: Int): TextView {
            return TextView(this).apply {
                text = symbol
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val normalBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2C"))
                    cornerRadius = dp(7).toFloat()
                    setStroke(1, Color.parseColor("#555555"))
                }
                val pressedBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#00897B"))
                    cornerRadius = dp(7).toFloat()
                }
                background = normalBg
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    setMargins(gap, gap, gap, gap)
                }

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.background = pressedBg
                            moveCrosshairBy(dx, dy, finalStep = false)
                            stopRepeatMove()
                            repeatRunnable = object : Runnable {
                                override fun run() {
                                    moveCrosshairBy(dx, dy, finalStep = false)
                                    uiHandler.postDelayed(this, 45L)
                                }
                            }
                            uiHandler.postDelayed(repeatRunnable!!, 300L)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.background = normalBg
                            stopRepeatMove()
                            moveCrosshairBy(0, 0, finalStep = true)
                            true
                        }
                        else -> false
                    }
                }
            }
        }

        val centerDragBtn = TextView(this).apply {
            text = "✥"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#00E676"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F1F1F"))
                cornerRadius = dp(7).toFloat()
                setStroke(1, Color.parseColor("#00E676"))
            }
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                setMargins(gap, gap, gap, gap)
            }
        }

        val rowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(createDirBtn("▲", 0, -1))
        }

        val rowMid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(createDirBtn("◀", -1, 0))
            addView(centerDragBtn)
            addView(createDirBtn("▶", 1, 0))
        }

        val rowBot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(createDirBtn("▼", 0, 1))
        }

        container.addView(rowTop)
        container.addView(rowMid)
        container.addView(rowBot)

        makeDraggable(centerDragBtn, dpadParams, wm, container, refreshOnMove = false)
        makeDraggable(container, dpadParams, wm, container, refreshOnMove = false)

        wm.addView(container, dpadParams)
        dpadView = container
    }

    private fun makeDraggable(
        touchView: View,
        layoutParams: WindowManager.LayoutParams,
        wm: WindowManager,
        targetViewToUpdate: View = touchView,
        refreshOnMove: Boolean = true
    ) {
        touchView.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0
            var initY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = layoutParams.x
                        initY = layoutParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initX + (e.rawX - touchX).toInt()
                        layoutParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(targetViewToUpdate, layoutParams)
                        } catch (_: Exception) {}
                        if (refreshOnMove) {
                            syncAndRefreshFromCrosshair(forceSample = false)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        layoutParams.x = initX + (e.rawX - touchX).toInt()
                        layoutParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(targetViewToUpdate, layoutParams)
                        } catch (_: Exception) {}
                        if (refreshOnMove) {
                            syncAndRefreshFromCrosshair(forceSample = true)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showHollowCrosshair(wm: WindowManager) {
        val sizePx = crosshairSizePx()
        val target = object : View(this) {
            private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat().coerceAtLeast(2f)
            }
            private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat().coerceAtLeast(2f)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val ringRadius = (sizePx * 0.23f).coerceAtLeast(8f)
                val gap = (sizePx * 0.18f).coerceAtLeast(6f)
                canvas.drawCircle(cx, cy, ringRadius, paintRing)

                canvas.drawLine(0f, cy, cx - gap, cy, paintLine)
                canvas.drawLine(cx + gap, cy, width.toFloat(), cy, paintLine)
                canvas.drawLine(cx, 0f, cx, cy - gap, paintLine)
                canvas.drawLine(cx, cy + gap, cx, height.toFloat(), paintLine)
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val cParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastPickedX - sizePx / 2
            y = lastPickedY - sizePx / 2
            applyCutoutMode(this)
        }
        cParamsRef = cParams

        target.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = cParams.x
                        initY = cParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        cParams.x = initX + (e.rawX - touchX).toInt()
                        cParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(target, cParams)
                        } catch (_: Exception) {}
                        syncAndRefreshFromCrosshair(forceSample = false)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cParams.x = initX + (e.rawX - touchX).toInt()
                        cParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(target, cParams)
                        } catch (_: Exception) {}
                        syncAndRefreshFromCrosshair(forceSample = true)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(target, cParams)
        crosshairView = target
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.postDelayed({ updateStatus() }, 250)
        } else if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (hasOverlayPermission()) {
                showFloatingPicker()
            } else {
                Toast.makeText(this, "Izin overlay diperlukan untuk menampilkan alat bidik", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createButton(title: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(colorHex))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(6), 0, dp(6))
        layoutParams = lp
        setOnClickListener { onClick() }
    }
}
