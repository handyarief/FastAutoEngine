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
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var lastPickedX = 540
    private var lastPickedY = 1200
    private var lastPickedHex = "#FF0000"

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
            text = "Mesin Pendeteksi 2 Warna < 2ms untuk Automate.\n\nGeser Lubang Bidik Kuning ke warna sasaran, lalu simpan sebagai Target Warna 1 atau Target Warna 2."
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

        mainLayout.addView(createButton("2. Munculkan Alat Bidik Warna 1 & 2 (🎯)", "#00897B") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 1002)
            } else {
                showFloatingPicker()
            }
        })

        mainLayout.addView(createButton("3. 🛑 Matikan Mesin & Hapus Notifikasi", "#C62828") {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            crosshairView?.let { wm.removeView(it); crosshairView = null }
            floatingView?.let { wm.removeView(it); floatingView = null }

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

    private fun showFloatingPicker() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (floatingView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE111111"))
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#00E676"))
            }
        }

        val infoTv = TextView(this).apply {
            text = "X: $lastPickedX, Y: $lastPickedY\nHex: $lastPickedHex"
            textSize = 12f
            setTextColor(Color.WHITE)
        }

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 36).apply {
                setMargins(0, 10, 0, 10)
            }
            setBackgroundColor(Color.RED)
        }

        val saveBtn1 = Button(this).apply {
            text = "🎯 Simpan ke Warna 1"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE).edit()
                    .putInt("picked_x", lastPickedX)
                    .putInt("picked_y", lastPickedY)
                    .putString("picked_hex", lastPickedHex)
                    .apply()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("color1", lastPickedHex))
                Toast.makeText(this@MainActivity, "Warna 1 Tersimpan: ($lastPickedX, $lastPickedY) $lastPickedHex", Toast.LENGTH_SHORT).show()
            }
        }

        val saveBtn2 = Button(this).apply {
            text = "🎯 Simpan ke Warna 2"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#8E24AA"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
            setOnClickListener {
                getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE).edit()
                    .putInt("picked_x2", lastPickedX)
                    .putInt("picked_y2", lastPickedY)
                    .putString("picked_hex2", lastPickedHex)
                    .apply()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("color2", lastPickedHex))
                Toast.makeText(this@MainActivity, "Warna 2 Tersimpan: ($lastPickedX, $lastPickedY) $lastPickedHex", Toast.LENGTH_SHORT).show()
            }
        }

        val closeBtn = Button(this).apply {
            text = "❌ Tutup Bidik"
            textSize = 10f
            setOnClickListener {
                crosshairView?.let { wm.removeView(it); crosshairView = null }
                floatingView?.let { wm.removeView(it); floatingView = null }
            }
        }

        panel.addView(infoTv)
        panel.addView(colorPreview)
        panel.addView(saveBtn1)
        panel.addView(saveBtn2)
        panel.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 200
        }

        panel.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y; touchX = e.rawX; touchY = e.rawY; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (e.rawX - touchX).toInt()
                        params.y = initY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(panel, params)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(panel, params)
        floatingView = panel
        showHollowCrosshair(wm, infoTv, colorPreview)
    }

    private fun showHollowCrosshair(wm: WindowManager, infoTv: TextView, colorPreview: View) {
        val sizePx = 120
        val target = object : View(this) {
            private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 7f
            }
            private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawCircle(cx, cy, 38f, paintOuter)
                canvas.drawCircle(cx, cy, 38f, paintRing)

                canvas.drawLine(0f, cy, cx - 14f, cy, paintLine)
                canvas.drawLine(cx + 14f, cy, width.toFloat(), cy, paintLine)
                canvas.drawLine(cx, 0f, cx, cy - 14f, paintLine)
                canvas.drawLine(cx, cy + 14f, cx, height.toFloat(), paintLine)
            }
        }

        val cParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastPickedX - sizePx / 2
            y = lastPickedY - sizePx / 2
        }

        target.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = cParams.x; initY = cParams.y; touchX = e.rawX; touchY = e.rawY; return true
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        cParams.x = initX + (e.rawX - touchX).toInt()
                        cParams.y = initY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(target, cParams)

                        val loc = IntArray(2)
                        target.getLocationOnScreen(loc)
                        val centerX = loc[0] + target.width / 2
                        val centerY = loc[1] + target.height / 2

                        lastPickedX = centerX
                        lastPickedY = centerY
                        val hex = ScreenCaptureService.getPixelHex(centerX, centerY) ?: "#000000"
                        lastPickedHex = hex

                        infoTv.text = "X: $centerX, Y: $centerY\nHex: $hex"
                        try { colorPreview.setBackgroundColor(Color.parseColor(hex)) } catch (_: Exception) {}
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
