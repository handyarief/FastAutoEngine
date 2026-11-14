
package com.fastauto.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FastAutoMain"
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_OVERLAY = 1002

        private const val SAMPLE_DELAY_MS = 55L
        private const val MAX_PICK_FRAME_AGE_MS = 750L
    }

    private lateinit var statusText: TextView

    private var floatingView: View? = null
    private var crosshairView: View? = null
    private var dpadView: View? = null

    private var lastPickedX = 540
    private var lastPickedY = 1200
    private var lastPickedHex = "#FF0000"

    private val uiHandler = Handler(Looper.getMainLooper())

    /*
     * Satu worker khusus pembacaan warna.
     * Screenshot fallback tidak akan memblokir UI.
     */
    private val colorExecutor =
        Executors.newSingleThreadExecutor { runnable ->

            Thread(
                runnable,
                "FastAutoColorPicker"
            ).apply {
                isDaemon = true
            }
        }

    /*
     * Nomor urut permintaan sampel.
     * Hasil sampel lama diabaikan apabila sudah ada
     * permintaan yang lebih baru.
     */
    private val sampleGeneration = AtomicLong(0L)

    private var repeatRunnable: Runnable? = null
    private var delayedSampleRunnable: Runnable? = null

    private var infoTvRef: TextView? = null
    private var hexTvRef: TextView? = null

    private var colorPreviewDrawable: GradientDrawable? = null

    private var cParamsRef: WindowManager.LayoutParams? = null

    private val crosshairSizePx = 96

    private var statusOffsetX = 0
    private var statusOffsetY = 0

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ==================================================
    // ACTIVITY LIFECYCLE
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadSavedSelection()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {

        removeAllOverlays()

        sampleGeneration.incrementAndGet()

        colorExecutor.shutdownNow()

        super.onDestroy()
    }

    private fun loadSavedSelection() {

        val prefs = getSharedPreferences(
            "fast_auto_prefs",
            Context.MODE_PRIVATE
        )

        lastPickedX = prefs.getInt(
            "picked_x",
            540
        )

        lastPickedY = prefs.getInt(
            "picked_y",
            1200
        )

        lastPickedHex = prefs.getString(
            "picked_hex",
            "#FF0000"
        ) ?: "#FF0000"
    }

    // ==================================================
    // MAIN UI
    // ==================================================

    private fun setupUI() {

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "⚡ FastAuto Color Plugin"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }

        mainLayout.addView(title)

        val subtitle = TextView(this).apply {
            text =
                "Mesin pendeteksi warna untuk Automate.\n\n" +
                "Geser lubang bidik kuning atau gunakan " +
                "Joystick D-Pad (Atas/Bawah/Kiri/Kanan) " +
                "untuk memilih titik piksel secara presisi."

            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 24)
        }

        mainLayout.addView(subtitle)

        statusText = TextView(this).apply {
            text = "Status Mesin: Memeriksa..."
            textSize = 15f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, 32)
        }

        mainLayout.addView(statusText)

        mainLayout.addView(
            createButton(
                "1. Aktifkan Mesin Baca Warna (Wajib)",
                "#8E24AA"
            ) {

                val manager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    REQUEST_CAPTURE
                )
            }
        )

        mainLayout.addView(
            createButton(
                "2. Munculkan Alat Bidik + Joystick (🎯)",
                "#00897B"
            ) {

                if (!Settings.canDrawOverlays(this)) {

                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )

                    startActivityForResult(
                        intent,
                        REQUEST_OVERLAY
                    )

                } else {

                    showFloatingPicker()
                }
            }
        )

        mainLayout.addView(
            createButton(
                "3. 🛑 Matikan Mesin & Hapus Notifikasi",
                "#C62828"
            ) {

                removeAllOverlays()

                val stopIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = "STOP_SERVICE"
                }

                startService(stopIntent)

                statusText.postDelayed(
                    { updateStatus() },
                    300
                )

                Toast.makeText(
                    this,
                    "Mesin pembaca warna berhasil dimatikan",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        setContentView(mainLayout)

        updateStatus()
    }

    private fun updateStatus() {

        val ready = ScreenCaptureService.isReady()

        val fresh = ScreenCaptureService.isFrameFresh(
            MAX_PICK_FRAME_AGE_MS
        )

        when {

            !ready -> {

                statusText.text =
                    "Mesin Baca Warna RAM: ❌ MATI"

                statusText.setTextColor(Color.RED)
            }

            !fresh -> {

                statusText.text =
                    "Mesin Baca Warna: ⚠ MENUNGGU FRAME BARU"

                statusText.setTextColor(
                    Color.parseColor("#FFB74D")
                )
            }

            else -> {

                val frame = ScreenCaptureService.getFrameInfo()

                statusText.text =
                    "Mesin Baca Warna RAM: ✅ AKTIF " +
                    "| Frame: ${frame?.receivedAgeMs ?: 0} ms"

                statusText.setTextColor(
                    Color.parseColor("#00E676")
                )
            }
        }
    }

    // ==================================================
    // SCREEN DIMENSIONS
    // ==================================================

    private fun getCaptureWidth(): Int {

        val width = ScreenCaptureService.screenWidth

        return if (width > 0) {
            width
        } else {
            resources.displayMetrics.widthPixels.coerceAtLeast(1)
        }
    }

    private fun getCaptureHeight(): Int {

        val height = ScreenCaptureService.screenHeight

        return if (height > 0) {
            height
        } else {
            resources.displayMetrics.heightPixels.coerceAtLeast(1)
        }
    }

    private fun clampX(x: Int): Int {
        return x.coerceIn(0, getCaptureWidth() - 1)
    }

    private fun clampY(y: Int): Int {
        return y.coerceIn(0, getCaptureHeight() - 1)
    }

    // ==================================================
    // OVERLAY CLEANUP
    // ==================================================

    private fun removeAllOverlays() {

        sampleGeneration.incrementAndGet()

        stopRepeatMove()

        delayedSampleRunnable?.let {
            uiHandler.removeCallbacks(it)
        }

        delayedSampleRunnable = null

        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        crosshairView?.let {

            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }

            crosshairView = null
        }

        floatingView?.let {

            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }

            floatingView = null
        }

        dpadView?.let {

            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }

            dpadView = null
        }

        infoTvRef = null
        hexTvRef = null

        colorPreviewDrawable = null
        cParamsRef = null
    }

    // ==================================================
    // CROSSHAIR COORDINATES
    // ==================================================

    private fun getCrosshairCenter(): Pair<Int, Int>? {

        val target = crosshairView
            ?: return null

        if (!target.isAttachedToWindow) {
            return null
        }

        return try {

            val loc = IntArray(2)

            target.getLocationOnScreen(loc)

            val x = loc[0] + target.width / 2
            val y = loc[1] + target.height / 2

            Pair(
                clampX(x),
                clampY(y)
            )

        } catch (_: Exception) {
            null
        }
    }

    /*
     * Sinkronisasi posisi crosshair.
     * Pengambilan sampel ditunda sedikit agar gerakan selesai.
     */
    private fun syncAndRefreshFromCrosshair() {

        val target = crosshairView
            ?: return

        target.post {

            if (crosshairView !== target) {
                return@post
            }

            val loc = IntArray(2)

            target.getLocationOnScreen(loc)

            val params = cParamsRef

            if (params != null) {

                statusOffsetX = loc[0] - params.x
                statusOffsetY = loc[1] - params.y
            }

            val x = clampX(
                loc[0] + target.width / 2
            )

            val y = clampY(
                loc[1] + target.height / 2
            )

            requestColorSample(
                x,
                y,
                SAMPLE_DELAY_MS
            )
        }
    }

    // ==================================================
    // COLOR READING ENGINE
    // ==================================================

    /*
     * Membaca warna dari RAM terlebih dahulu.
     * Accessibility Screenshot hanya menjadi fallback.
     *
     * Fungsi ini harus dijalankan di background.
     */
    private fun readColorAt(
        x: Int,
        y: Int
    ): String? {

        if (
            x !in 0 until getCaptureWidth() ||
            y !in 0 until getCaptureHeight()
        ) {
            return null
        }

        // Jalur pertama: RAM Direct Pixel.
        if (
            ScreenCaptureService.isFrameFresh(
                MAX_PICK_FRAME_AGE_MS
            )
        ) {

            val batch =
                ScreenCaptureService.getPixelRgbBatch(
                    intArrayOf(x, y)
                )

            if (batch != null) {

                val rgb = batch.rgbValues.firstOrNull()

                if (
                    rgb != null &&
                    rgb >= 0 &&
                    batch.receivedAgeMs <= MAX_PICK_FRAME_AGE_MS
                ) {

                    return String.format(
                        Locale.US,
                        "#%06X",
                        rgb and 0xFFFFFF
                    )
                }
            }
        }

        // Jalur kedua: Accessibility Screenshot.
        return try {

            FastAccessibilityService.getStealthPixelHex(
                x,
                y
            )

        } catch (e: Exception) {

            android.util.Log.e(
                TAG,
                "Pembacaan warna gagal",
                e
            )

            null
        }
    }

    /*
     * Permintaan sampel untuk tampilan crosshair.
     *
     * Permintaan terbaru akan membatalkan hasil
     * permintaan yang lebih lama.
     */
    private fun requestColorSample(
        x: Int,
        y: Int,
        delayMs: Long = SAMPLE_DELAY_MS
    ) {

        val requestId =
            sampleGeneration.incrementAndGet()

        lastPickedX = clampX(x)
        lastPickedY = clampY(y)

        infoTvRef?.text =
            "X: $lastPickedX   Y: $lastPickedY"

        hexTvRef?.text = "Membaca..."

        delayedSampleRunnable?.let {
            uiHandler.removeCallbacks(it)
        }

        val runnable = Runnable {

            if (requestId != sampleGeneration.get()) {
                return@Runnable
            }

            delayedSampleRunnable = null

            colorExecutor.execute {

                if (requestId != sampleGeneration.get()) {
                    return@execute
                }

                val sampledHex = readColorAt(
                    lastPickedX,
                    lastPickedY
                )

                uiHandler.post {

                    if (
                        requestId != sampleGeneration.get() ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@post
                    }

                    if (sampledHex == null) {

                        hexTvRef?.text =
                            "FRAME TIDAK TERSEDIA"

                    } else {

                        lastPickedHex = sampledHex

                        updateColorDisplay(sampledHex)
                    }

                    updateStatus()
                }
            }
        }

        delayedSampleRunnable = runnable

        uiHandler.postDelayed(
            runnable,
            delayMs.coerceAtLeast(0L)
        )
    }

    private fun updateColorDisplay(hex: String) {

        hexTvRef?.text = hex

        try {

            colorPreviewDrawable?.setColor(
                Color.parseColor(hex)
            )

        } catch (_: Exception) {
        }
    }

    // ==================================================
    // SAVE COLOR 1 / COLOR 2
    // ==================================================

    /*
     * Penting:
     * Koordinat diambil pada saat tombol ditekan.
     * Pembacaan warna dilakukan di background.
     * Penyimpanan hanya terjadi jika sampel valid.
     */
    private fun savePickedColor(
        colorNumber: Int
    ) {

        if (colorNumber !in 1..2) {
            return
        }

        val coordinates = getCrosshairCenter()

        if (coordinates == null) {

            Toast.makeText(
                this,
                "Alat bidik belum siap",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val x = coordinates.first
        val y = coordinates.second

        Toast.makeText(
            this,
            "Mengambil sampel Warna $colorNumber...",
            Toast.LENGTH_SHORT
        ).show()

        colorExecutor.execute {

            val hex = readColorAt(x, y)

            uiHandler.post {

                if (isFinishing || isDestroyed) {
                    return@post
                }

                if (hex == null) {

                    Toast.makeText(
                        this,
                        "Gagal membaca warna. Periksa mesin capture.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@post
                }

                val prefs = getSharedPreferences(
                    "fast_auto_prefs",
                    Context.MODE_PRIVATE
                )

                val editor = prefs.edit()

                if (colorNumber == 1) {

                    editor
                        .putInt("picked_x", x)
                        .putInt("picked_y", y)
                        .putString("picked_hex", hex)

                } else {

                    editor
                        .putInt("picked_x2", x)
                        .putInt("picked_y2", y)
                        .putString("picked_hex2", hex)
                }

                editor.apply()

                // Salin HEX ke clipboard.
                try {

                    val clipboard = getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "color$colorNumber",
                            hex
                        )
                    )

                } catch (_: Exception) {
                }

                Toast.makeText(
                    this,
                    "W$colorNumber Tersimpan: ($x, $y) $hex",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ==================================================
    // CROSSHAIR MOVEMENT
    // ==================================================

    private fun moveCrosshairBy(
        dx: Int,
        dy: Int
    ) {

        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        val target = crosshairView
            ?: return

        val params = cParamsRef
            ?: return

        val loc = IntArray(2)

        target.getLocationOnScreen(loc)

        val currentX =
            loc[0] + target.width / 2

        val currentY =
            loc[1] + target.height / 2

        val nextX = clampX(currentX + dx)
        val nextY = clampY(currentY + dy)

        params.x =
            nextX - target.width / 2 - statusOffsetX

        params.y =
            nextY - target.height / 2 - statusOffsetY

        try {

            wm.updateViewLayout(
                target,
                params
            )

        } catch (_: Exception) {
        }

        syncAndRefreshFromCrosshair()
    }

    private fun stopRepeatMove() {

        repeatRunnable?.let {
            uiHandler.removeCallbacks(it)
        }

        repeatRunnable = null
    }

    // ==================================================
    // FLOATING COLOR PICKER
    // ==================================================

    private fun showFloatingPicker() {

        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        if (floatingView != null) {
            return
        }

        val panel = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#E6141414")
                )

                cornerRadius = dp(12).toFloat()

                setStroke(
                    dp(1).coerceAtLeast(2),
                    Color.parseColor("#00E676")
                )
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val swatchBg = GradientDrawable().apply {

            setColor(Color.RED)

            cornerRadius = dp(6).toFloat()

            setStroke(
                dp(2),
                Color.WHITE
            )
        }

        colorPreviewDrawable = swatchBg

        val colorPreview = View(this).apply {

            layoutParams = LinearLayout.LayoutParams(
                dp(36),
                dp(36)
            ).apply {
                setMargins(0, 0, dp(8), 0)
            }

            background = swatchBg
        }

        val textCol = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val infoTv = TextView(this).apply {

            text = "X: $lastPickedX   Y: $lastPickedY"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                11.5f
            )

            setTextColor(
                Color.parseColor("#E0E0E0")
            )

            setTypeface(
                Typeface.MONOSPACE,
                Typeface.BOLD
            )

            setSingleLine(true)
        }

        infoTvRef = infoTv

        val hexTv = TextView(this).apply {

            text = lastPickedHex

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                13.5f
            )

            setTextColor(
                Color.parseColor("#00E676")
            )

            setTypeface(
                Typeface.MONOSPACE,
                Typeface.BOLD
            )

            setSingleLine(true)
        }

        hexTvRef = hexTv

        textCol.addView(infoTv)
        textCol.addView(hexTv)

        val closeBtn = TextView(this).apply {

            text = "✕"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                13f
            )

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#C62828")
                )

                cornerRadius = dp(6).toFloat()
            }

            layoutParams = LinearLayout.LayoutParams(
                dp(26),
                dp(26)
            ).apply {
                setMargins(dp(6), 0, 0, 0)
            }

            setOnClickListener {
                removeAllOverlays()
            }
        }

        topRow.addView(colorPreview)
        topRow.addView(textCol)
        topRow.addView(closeBtn)

        // ----------------------------------------------
        // SAVE BUTTONS
        // ----------------------------------------------

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

            text = "🎯 Warna 1"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                11f
            )

            setTextColor(Color.WHITE)

            setTypeface(
                null,
                Typeface.BOLD
            )

            gravity = Gravity.CENTER

            setPadding(
                dp(8),
                dp(6),
                dp(8),
                dp(6)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#1E88E5")
                )

                cornerRadius = dp(6).toFloat()
            }

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, dp(4), 0)
            }

            setOnClickListener {
                savePickedColor(1)
            }
        }

        val saveBtn2 = TextView(this).apply {

            text = "🎯 Warna 2"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                11f
            )

            setTextColor(Color.WHITE)

            setTypeface(
                null,
                Typeface.BOLD
            )

            gravity = Gravity.CENTER

            setPadding(
                dp(8),
                dp(6),
                dp(8),
                dp(6)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#8E24AA")
                )

                cornerRadius = dp(6).toFloat()
            }

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(4), 0, 0, 0)
            }

            setOnClickListener {
                savePickedColor(2)
            }
        }

        btnRow.addView(saveBtn1)
        btnRow.addView(saveBtn2)

        panel.addView(topRow)
        panel.addView(btnRow)

        val overlayType = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        } else {

            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            dp(215),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.START

            x = dp(12)
            y = dp(100)
        }

        makeDraggable(
            panel,
            params,
            wm
        )

        wm.addView(panel, params)

        floatingView = panel

        showHollowCrosshair(wm)
        showDraggableJoystick(wm)

        syncAndRefreshFromCrosshair()
    }

    // ==================================================
    // DRAGGABLE JOYSTICK
    // ==================================================

    private fun showDraggableJoystick(
        wm: WindowManager
    ) {

        if (dpadView != null) {
            return
        }

        val overlayType = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        } else {

            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dpadParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.START

            x = dp(240)
            y = dp(100)
        }

        val container = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER

            setPadding(
                dp(5),
                dp(5),
                dp(5),
                dp(5)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#D9181818")
                )

                cornerRadius = dp(14).toFloat()

                setStroke(
                    dp(1).coerceAtLeast(2),
                    Color.parseColor("#00E676")
                )
            }
        }

        val btnSize = dp(32)
        val gap = dp(2)

        fun createDirBtn(
            symbol: String,
            dx: Int,
            dy: Int
        ): TextView {

            return TextView(this).apply {

                text = symbol

                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    13f
                )

                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER

                val normalBg = GradientDrawable().apply {

                    setColor(
                        Color.parseColor("#2C2C2C")
                    )

                    cornerRadius = dp(7).toFloat()

                    setStroke(
                        1,
                        Color.parseColor("#555555")
                    )
                }

                val pressedBg = GradientDrawable().apply {

                    setColor(
                        Color.parseColor("#00897B")
                    )

                    cornerRadius = dp(7).toFloat()
                }

                background = normalBg

                layoutParams = LinearLayout.LayoutParams(
                    btnSize,
                    btnSize
                ).apply {

                    setMargins(
                        gap,
                        gap,
                        gap,
                        gap
                    )
                }

                setOnTouchListener { view, event ->

                    when (event.action) {

                        MotionEvent.ACTION_DOWN -> {

                            view.background = pressedBg

                            moveCrosshairBy(dx, dy)

                            stopRepeatMove()

                            repeatRunnable = object : Runnable {

                                override fun run() {

                                    moveCrosshairBy(dx, dy)

                                    uiHandler.postDelayed(
                                        this,
                                        45L
                                    )
                                }
                            }

                            uiHandler.postDelayed(
                                repeatRunnable!!,
                                300L
                            )

                            true
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {

                            view.background = normalBg

                            stopRepeatMove()

                            true
                        }

                        else -> false
                    }
                }
            }
        }

        val centerDragBtn = TextView(this).apply {

            text = "✥"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                14f
            )

            setTextColor(
                Color.parseColor("#00E676")
            )

            gravity = Gravity.CENTER

            background = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#1F1F1F")
                )

                cornerRadius = dp(7).toFloat()

                setStroke(
                    1,
                    Color.parseColor("#00E676")
                )
            }

            layoutParams = LinearLayout.LayoutParams(
                btnSize,
                btnSize
            ).apply {

                setMargins(
                    gap,
                    gap,
                    gap,
                    gap
                )
            }
        }

        val rowTop = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

            addView(
                createDirBtn("▲", 0, -1)
            )
        }

        val rowMid = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

            addView(
                createDirBtn("◀", -1, 0)
            )

            addView(centerDragBtn)

            addView(
                createDirBtn("▶", 1, 0)
            )
        }

        val rowBot = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

            addView(
                createDirBtn("▼", 0, 1)
            )
        }

        container.addView(rowTop)
        container.addView(rowMid)
        container.addView(rowBot)

        makeDraggable(
            centerDragBtn,
            dpadParams,
            wm,
            container
        )

        makeDraggable(
            container,
            dpadParams,
            wm,
            container
        )

        wm.addView(
            container,
            dpadParams
        )

        dpadView = container
    }

    // ==================================================
    // DRAGGABLE VIEW
    // ==================================================

    private fun makeDraggable(
        touchView: View,
        layoutParams: WindowManager.LayoutParams,
        wm: WindowManager,
        targetViewToUpdate: View = touchView
    ) {

        touchView.setOnTouchListener(
            object : View.OnTouchListener {

                var initX = 0
                var initY = 0

                var touchX = 0f
                var touchY = 0f

                override fun onTouch(
                    view: View,
                    event: MotionEvent
                ): Boolean {

                    when (event.action) {

                        MotionEvent.ACTION_DOWN -> {

                            initX = layoutParams.x
                            initY = layoutParams.y

                            touchX = event.rawX
                            touchY = event.rawY

                            return true
                        }

                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP -> {

                            layoutParams.x =
                                initX +
                                (event.rawX - touchX).toInt()

                            layoutParams.y =
                                initY +
                                (event.rawY - touchY).toInt()

                            try {

                                wm.updateViewLayout(
                                    targetViewToUpdate,
                                    layoutParams
                                )

                            } catch (_: Exception) {
                            }

                            syncAndRefreshFromCrosshair()

                            return true
                        }
                    }

                    return false
                }
            }
        )
    }

    // ==================================================
    // HOLLOW CROSSHAIR
    // ==================================================

    private fun showHollowCrosshair(
        wm: WindowManager
    ) {

        val sizePx = crosshairSizePx

        val target = object : View(this) {

            private val paintRing =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {

                    color = Color.YELLOW

                    style = Paint.Style.STROKE

                    strokeWidth = 3.5f
                }

            private val paintOuter =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {

                    color = Color.BLACK

                    style = Paint.Style.STROKE

                    strokeWidth = 5.5f
                }

            private val paintLine =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {

                    color = Color.CYAN

                    style = Paint.Style.STROKE

                    strokeWidth = 3f
                }

            override fun onDraw(canvas: Canvas) {

                super.onDraw(canvas)

                val cx = width / 2f
                val cy = height / 2f

                canvas.drawCircle(
                    cx,
                    cy,
                    30f,
                    paintOuter
                )

                canvas.drawCircle(
                    cx,
                    cy,
                    30f,
                    paintRing
                )

                canvas.drawLine(
                    0f,
                    cy,
                    cx - 12f,
                    cy,
                    paintLine
                )

                canvas.drawLine(
                    cx + 12f,
                    cy,
                    width.toFloat(),
                    cy,
                    paintLine
                )

                canvas.drawLine(
                    cx,
                    0f,
                    cx,
                    cy - 12f,
                    paintLine
                )

                canvas.drawLine(
                    cx,
                    cy + 12f,
                    cx,
                    height.toFloat(),
                    paintLine
                )
            }
        }

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val cParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            } else {

                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.START

            x = clampX(lastPickedX) - sizePx / 2
            y = clampY(lastPickedY) - sizePx / 2

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        cParamsRef = cParams

        target.setOnTouchListener(
            object : View.OnTouchListener {

                var initX = 0
                var initY = 0

                var touchX = 0f
                var touchY = 0f

                override fun onTouch(
                    view: View,
                    event: MotionEvent
                ): Boolean {

                    when (event.action) {

                        MotionEvent.ACTION_DOWN -> {

                            initX = cParams.x
                            initY = cParams.y

                            touchX = event.rawX
                            touchY = event.rawY

                            return true
                        }

                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP -> {

                            cParams.x =
                                initX +
                                (event.rawX - touchX).toInt()

                            cParams.y =
                                initY +
                                (event.rawY - touchY).toInt()

                            try {

                                wm.updateViewLayout(
                                    target,
                                    cParams
                                )

                            } catch (_: Exception) {
                            }

                            syncAndRefreshFromCrosshair()

                            return true
                        }
                    }

                    return false
                }
            }
        )

        wm.addView(
            target,
            cParams
        )

        crosshairView = target
    }

    // ==================================================
    // SCREEN CAPTURE PERMISSION
    // ==================================================

    @Deprecated("Legacy Activity Result API retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode == REQUEST_CAPTURE &&
            resultCode == Activity.RESULT_OK &&
            data != null
        ) {

            val serviceIntent = Intent(
                this,
                ScreenCaptureService::class.java
            ).apply {

                putExtra(
                    "RESULT_CODE",
                    resultCode
                )

                putExtra(
                    "DATA",
                    data
                )
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {

                startForegroundService(serviceIntent)

            } else {

                startService(serviceIntent)
            }

            statusText.postDelayed(
                { updateStatus() },
                500
            )

        } else if (
            requestCode == REQUEST_CAPTURE
        ) {

            Toast.makeText(
                this,
                "Izin tangkapan layar tidak diberikan",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (
            requestCode == REQUEST_OVERLAY &&
            Settings.canDrawOverlays(this)
        ) {

            showFloatingPicker()
        }
    }

    // ==================================================
    // BUTTON FACTORY
    // ==================================================

    private fun createButton(
        title: String,
        colorHex: String,
        onClick: () -> Unit
    ): Button {

        return Button(this).apply {

            text = title

            setTextColor(Color.WHITE)

            setBackgroundColor(
                Color.parseColor(colorHex)
            )

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            lp.setMargins(
                0,
                12,
                0,
                12
            )

            layoutParams = lp

            setOnClickListener {
                onClick()
            }
        }
    }
}
