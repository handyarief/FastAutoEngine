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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var floatingView: View? = null
    private var crosshairView: View? = null
    private var dpadView: View? = null

    private var lastPickedX = 540
    private var lastPickedY = 1200
    private var lastPickedHex = "#FF0000"

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var delayedSampleRunnable: Runnable? = null

    private var infoTvRef: TextView? = null
    private var hexTvRef: TextView? = null
    private var colorPreviewDrawable: GradientDrawable? = null
    private var cParamsRef: WindowManager.LayoutParams? = null
    private val crosshairSizePx = 96

    // Offset otomatis jika sistem tetap memberi pergeseran inset pada perangkat tertentu
    private var statusOffsetX = 0
    private var statusOffsetY = 0

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
    }

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
            text = "Mesin Pendeteksi 2 Warna < 2ms untuk Automate.\n\nGeser Lubang Bidik Kuning atau gunakan Joystick D-Pad (Atas/Bawah/Kiri/Kanan) untuk mengepaskan titik piksel secara presisi."
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

        mainLayout.addView(createButton("1. Aktifkan Mesin Baca Warna (Wajib)", "#8E24AA") {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), 1001)
        })

        mainLayout.addView(createButton("2. Munculkan Alat Bidik + Joystick (🎯)", "#00897B") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 1002)
            } else {
                showFloatingPicker()
            }
        })

        mainLayout.addView(createButton("3. 🛑 Matikan Mesin & Hapus Notifikasi", "#C62828") {
            removeAllOverlays()
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = "STOP_SERVICE"
            }
            startService(stopIntent)
            statusText.postDelayed({ updateStatus() }, 300)
            Toast.makeText(this, "Mesin pembaca warna berhasil dimatikan", Toast.LENGTH_SHORT).show()
        })

        setContentView(mainLayout)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val colorOk = ScreenCaptureService.isReady()
        statusText.text = "Mesin Baca Warna RAM: ${if (colorOk) "✅ AKTIF (Siap dipakai Automate)" else "❌ MATI"}"
    }

    private fun removeAllOverlays() {
        stopRepeatMove()
        delayedSampleRunnable?.let { uiHandler.removeCallbacks(it) }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {}; crosshairView = null }
        floatingView?.let { try { wm.removeView(it) } catch (_: Exception) {}; floatingView = null }
        dpadView?.let { try { wm.removeView(it) } catch (_: Exception) {}; dpadView = null }
    }

    /**
     * Membaca posisi fisik sesungguhnya dari titik tengah lubang Crosshair di layar penuh (termasuk Status Bar & Cutout)
     */
    private fun syncAndRefreshFromCrosshair() {
        val target = crosshairView ?: return
        target.post {
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)

            val cParams = cParamsRef
            if (cParams != null) {
                statusOffsetX = loc[0] - cParams.x
                statusOffsetY = loc[1] - cParams.y
            }

            val trueCenterX = loc[0] + (target.width / 2)
            val trueCenterY = loc[1] + (target.height / 2)
            refreshColorAt(trueCenterX, trueCenterY)

            // Baca ulang setelah frame baru MediaProjection dirender (45ms kemudian)
            delayedSampleRunnable?.let { uiHandler.removeCallbacks(it) }
            delayedSampleRunnable = Runnable {
                val locAfter = IntArray(2)
                target.getLocationOnScreen(locAfter)
                val cx = locAfter[0] + (target.width / 2)
                val cy = locAfter[1] + (target.height / 2)
                refreshColorAt(cx, cy)
            }
            uiHandler.postDelayed(delayedSampleRunnable!!, 45L)
        }
    }

    private fun refreshColorAt(x: Int, y: Int) {
        val maxW = ScreenCaptureService.screenWidth.coerceAtLeast(1080)
        val maxH = ScreenCaptureService.screenHeight.coerceAtLeast(1920)
        lastPickedX = x.coerceIn(0, maxW - 1)
        lastPickedY = y.coerceIn(0, maxH - 1)

        val hex = ScreenCaptureService.getPixelHex(lastPickedX, lastPickedY)
            ?: FastAccessibilityService.getStealthPixelHex(lastPickedX, lastPickedY)
            ?: "#000000"
        lastPickedHex = hex

        infoTvRef?.text = "X: $lastPickedX   Y: $lastPickedY"
        hexTvRef?.text = hex
        try {
            colorPreviewDrawable?.setColor(Color.parseColor(hex))
        } catch (_: Exception) {}
    }

    private fun moveCrosshairBy(dx: Int, dy: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val target = crosshairView ?: return
        val cParams = cParamsRef ?: return

        val maxW = ScreenCaptureService.screenWidth.coerceAtLeast(1080)
        val maxH = ScreenCaptureService.screenHeight.coerceAtLeast(1920)

        // Ambil posisi layar fisik saat ini lalu geser sejauh dx, dy
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val currentScreenX = loc[0] + (target.width / 2)
        val currentScreenY = loc[1] + (target.height / 2)

        val nextScreenX = (currentScreenX + dx).coerceIn(0, maxW - 1)
        val nextScreenY = (currentScreenY + dy).coerceIn(0, maxH - 1)

        // Konversi balik ke koordinat WindowManager dengan mengkompensasi offset sistem (jika ada)
        cParams.x = nextScreenX - (crosshairSizePx / 2) - statusOffsetX
        cParams.y = nextScreenY - (crosshairSizePx / 2) - statusOffsetY

        try {
            wm.updateViewLayout(target, cParams)
        } catch (_: Exception) {}

        syncAndRefreshFromCrosshair()
    }

    private fun stopRepeatMove() {
        repeatRunnable?.let { uiHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun showFloatingPicker() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (floatingView != null) return

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

        val swatchBg = GradientDrawable().apply {
            setColor(Color.RED)
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
            text = "🎯 Warna 1"
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
            setOnClickListener {
                syncAndRefreshFromCrosshair()
                getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE).edit()
                    .putInt("picked_x", lastPickedX)
                    .putInt("picked_y", lastPickedY)
                    .putString("picked_hex", lastPickedHex)
                    .apply()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("color1", lastPickedHex))
                Toast.makeText(this@MainActivity, "W1 Tersimpan: ($lastPickedX, $lastPickedY) $lastPickedHex", Toast.LENGTH_SHORT).show()
            }
        }

        val saveBtn2 = TextView(this).apply {
            text = "🎯 Warna 2"
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
            setOnClickListener {
                syncAndRefreshFromCrosshair()
                getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE).edit()
                    .putInt("picked_x2", lastPickedX)
                    .putInt("picked_y2", lastPickedY)
                    .putString("picked_hex2", lastPickedHex)
                    .apply()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("color2", lastPickedHex))
                Toast.makeText(this@MainActivity, "W2 Tersimpan: ($lastPickedX, $lastPickedY) $lastPickedHex", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(saveBtn1)
        btnRow.addView(saveBtn2)

        panel.addView(topRow)
        panel.addView(btnRow)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

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
        }

        makeDraggable(panel, params, wm)

        wm.addView(panel, params)
        floatingView = panel

        showHollowCrosshair(wm)
        showDraggableJoystick(wm)
        syncAndRefreshFromCrosshair()
    }

    private fun showDraggableJoystick(wm: WindowManager) {
        if (dpadView != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

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
                            moveCrosshairBy(dx, dy)
                            stopRepeatMove()
                            repeatRunnable = object : Runnable {
                                override fun run() {
                                    moveCrosshairBy(dx, dy)
                                    uiHandler.postDelayed(this, 45L)
                                }
                            }
                            uiHandler.postDelayed(repeatRunnable!!, 300L)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.background = normalBg
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

        makeDraggable(centerDragBtn, dpadParams, wm, container)
        makeDraggable(container, dpadParams, wm, container)

        wm.addView(container, dpadParams)
        dpadView = container
    }

    private fun makeDraggable(
        touchView: View,
        layoutParams: WindowManager.LayoutParams,
        wm: WindowManager,
        targetViewToUpdate: View = touchView
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
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        layoutParams.x = initX + (e.rawX - touchX).toInt()
                        layoutParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(targetViewToUpdate, layoutParams)
                        } catch (_: Exception) {}
                        // Segarkan juga bacaan warna bila panel digeser menjauh dari crosshair
                        syncAndRefreshFromCrosshair()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showHollowCrosshair(wm: WindowManager) {
        val sizePx = crosshairSizePx
        val target = object : View(this) {
            private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
            }
            private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 5.5f
            }
            private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                // Lubang tengah dibuat berjarak 12px dari titik pusat agar anti-aliasing garis tidak pernah mengotori piksel tengah
                canvas.drawCircle(cx, cy, 30f, paintOuter)
                canvas.drawCircle(cx, cy, 30f, paintRing)

                canvas.drawLine(0f, cy, cx - 12f, cy, paintLine)
                canvas.drawLine(cx + 12f, cy, width.toFloat(), cy, paintLine)
                canvas.drawLine(cx, 0f, cx, cy - 12f, paintLine)
                canvas.drawLine(cx, cy + 12f, cx, height.toFloat(), paintLine)
            }
        }

        // KUNCI UTAMA AKURASI: Gunakan FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS + CUTOUT_MODE_ALWAYS
        // agar sistem koordinat (0,0) WindowManager sama persis dengan pojok kiri-atas layar fisik MediaProjection
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val cParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastPickedX - sizePx / 2
            y = lastPickedY - sizePx / 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        cParams.x = initX + (e.rawX - touchX).toInt()
                        cParams.y = initY + (e.rawY - touchY).toInt()
                        try {
                            wm.updateViewLayout(target, cParams)
                        } catch (_: Exception) {}

                        syncAndRefreshFromCrosshair()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(target, cParams)
        crosshairView = target
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.postDelayed({ updateStatus() }, 500)
        }
    }

    private fun createButton(title: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(colorHex))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 12, 0, 12)
        layoutParams = lp
        setOnClickListener { onClick() }
    }
}
