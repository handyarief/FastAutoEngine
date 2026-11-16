package com.fastauto.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class PluginConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        val lastX1 = prefs.getInt("picked_x", 540)
        val lastY1 = prefs.getInt("picked_y", 1200)
        val lastHex1 = prefs.getString("picked_hex", "#FF0000") ?: "#FF0000"

        val lastX2 = prefs.getInt("picked_x2", 540)
        val lastY2 = prefs.getInt("picked_y2", 1200)
        val lastHex2 = prefs.getString("picked_hex2", "#00FF00") ?: "#00FF00"

        val oldBundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "⚡ Mode Tunggal: Cari Warna di Area Sekitar"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(title)

        val subInfo = TextView(this).apply {
            text = "Arsitektur Direct ByteBuffer Zero-Copy (< 0.01ms, Akurat 100%, Anti-Lag & Dingin)."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(subInfo)

        // ================= TARGET WARNA 1 =================
        val pickBtn1 = Button(this).apply {
            text = "🎯 Ambil Bidik Area 1: ($lastX1, $lastY1) $lastHex1"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 12, 0, 8)
            layoutParams = lp
        }
        layout.addView(pickBtn1)

        val x1Input = createInput("Pusat X1", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("x", lastX1) ?: lastX1).toString())
        val y1Input = createInput("Pusat Y1", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("y", lastY1) ?: lastY1).toString())
        val hex1Input = createInput("Target Warna 1 Hex (#RRGGBB)", InputType.TYPE_CLASS_TEXT, oldBundle?.getString("hex", lastHex1) ?: lastHex1)

        pickBtn1.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            x1Input.setText(p.getInt("picked_x", 540).toString())
            y1Input.setText(p.getInt("picked_y", 1200).toString())
            hex1Input.setText(p.getString("picked_hex", "#FF0000"))
        }

        layout.addView(createLabel("Koordinat Pusat X1 (Area 1):"))
        layout.addView(x1Input)
        layout.addView(createLabel("Koordinat Pusat Y1 (Area 1):"))
        layout.addView(y1Input)
        layout.addView(createLabel("Target Warna 1 Hex (Contoh #FF0000):"))
        layout.addView(hex1Input)

        // ================= TARGET WARNA 2 =================
        val useSecondColorCheck = CheckBox(this).apply {
            text = "Aktifkan Target Area Warna 2 (Pencarian Ganda)"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 15f
            isChecked = oldBundle?.getBoolean("useColor2", false) ?: false
            setPadding(0, 28, 0, 8)
        }
        layout.addView(useSecondColorCheck)

        val pickBtn2 = Button(this).apply {
            text = "🎯 Ambil Bidik Area 2: ($lastX2, $lastY2) $lastHex2"
            setBackgroundColor(Color.parseColor("#8E24AA"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
        }
        layout.addView(pickBtn2)

        val logicModes = arrayOf(
            "0: DAN (AND) -> Area 1 & Area 2 Harus Ditemukan Semua",
            "1: ATAU (OR) -> Salah Satu Area Ditemukan Saja Cukup"
        )
        val logicSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PluginConfigActivity, android.R.layout.simple_spinner_dropdown_item, logicModes)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setSelection(oldBundle?.getInt("colorLogic", 0) ?: 0)
        }

        val x2Input = createInput("Pusat X2", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("x2", lastX2) ?: lastX2).toString())
        val y2Input = createInput("Pusat Y2", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("y2", lastY2) ?: lastY2).toString())
        val hex2Input = createInput("Target Warna 2 Hex (#RRGGBB)", InputType.TYPE_CLASS_TEXT, oldBundle?.getString("hex2", lastHex2) ?: lastHex2)

        pickBtn2.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            x2Input.setText(p.getInt("picked_x2", 540).toString())
            y2Input.setText(p.getInt("picked_y2", 1200).toString())
            hex2Input.setText(p.getString("picked_hex2", "#00FF00"))
            useSecondColorCheck.isChecked = true
        }

        layout.addView(createLabel("Logika Pencarian 2 Area:"))
        layout.addView(logicSpinner)
        layout.addView(createLabel("Koordinat Pusat X2 (Area 2):"))
        layout.addView(x2Input)
        layout.addView(createLabel("Koordinat Pusat Y2 (Area 2):"))
        layout.addView(y2Input)
        layout.addView(createLabel("Target Warna 2 Hex (Contoh #00FF00):"))
        layout.addView(hex2Input)

        // ================= PENGATURAN AREA & KECEPATAN =================
        val radiusInput = createInput("Radius Area Piksel (Default 100)", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("radius", 100) ?: 100).toString())
        val stepInput = createInput("Kerapatan Langkah Piksel (1 = Sangat Rapat, 4 = Super Cepat)", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("step", 4) ?: 4).toString())
        val tolInput = createInput("Toleransi Kemiripan Warna (0 - 100)", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("tolerance", 20) ?: 20).toString())
        val timeoutInput = createInput("0 = Cek Instan <0.01ms | >0 = Tunggu Maks (ms) tanpa Loop", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getLong("timeout", 0L) ?: 0L).toString())

        layout.addView(createLabel("Radius Area Pencarian (± piksel dari titik pusat):"))
        layout.addView(radiusInput)
        layout.addView(createLabel("Langkah Pemindaian / Step (2 - 4 disarankan):"))
        layout.addView(stepInput)
        layout.addView(createLabel("Toleransi Warna (15 - 25 disarankan):"))
        layout.addView(tolInput)
        layout.addView(createLabel("Batas Tunggu Frame-Sync ms (Isi 0 untuk Instan <0.01ms):"))
        layout.addView(timeoutInput)

        val saveBtn = Button(this).apply {
            text = "✅ SIMPAN KE BLOK AUTOMATE"
            setBackgroundColor(Color.parseColor("#43A047"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 40, 0, 0)
            layoutParams = lp

            setOnClickListener {
                val x1 = x1Input.text.toString().toIntOrNull() ?: 540
                val y1 = y1Input.text.toString().toIntOrNull() ?: 1200
                val hex1 = hex1Input.text.toString().trim().ifEmpty { "#FF0000" }
                val rgb1 = ScreenCaptureService.parseHexToInt(hex1)

                val useColor2 = useSecondColorCheck.isChecked
                val colorLogic = logicSpinner.selectedItemPosition
                val x2 = x2Input.text.toString().toIntOrNull() ?: 540
                val y2 = y2Input.text.toString().toIntOrNull() ?: 1200
                val hex2 = hex2Input.text.toString().trim().ifEmpty { "#00FF00" }
                val rgb2 = ScreenCaptureService.parseHexToInt(hex2)

                val radius = (radiusInput.text.toString().toIntOrNull() ?: 100).coerceIn(1, 500)
                val step = (stepInput.text.toString().toIntOrNull() ?: 4).coerceIn(1, 20)
                val tol = (tolInput.text.toString().toIntOrNull() ?: 20).coerceIn(0, 255)
                val timeout = (timeoutInput.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 8000L)

                ScreenCaptureService.registerWatchConfig(
                    x1, y1, rgb1,
                    useColor2, colorLogic,
                    x2, y2, rgb2,
                    radius, step, tol
                )

                val resultBundle = Bundle().apply {
                    putInt("mode", 2)
                    putInt("x", x1)
                    putInt("y", y1)
                    putString("hex", hex1)
                    putInt("rgb1", rgb1)
                    putBoolean("useColor2", useColor2)
                    putInt("colorLogic", colorLogic)
                    putInt("x2", x2)
                    putInt("y2", y2)
                    putString("hex2", hex2)
                    putInt("rgb2", rgb2)
                    putInt("radius", radius)
                    putInt("step", step)
                    putInt("tolerance", tol)
                    putLong("timeout", timeout)
                }

                val logicStr = if (colorLogic == 0) "AND" else "OR"
                val waitTag = if (timeout > 0L) "Wait ${timeout}ms" else "<0.01ms"
                val blurb = if (useColor2) {
                    "Area(±${radius}px, $waitTag): W1($x1,$y1)=$hex1 $logicStr W2($x2,$y2)=$hex2"
                } else {
                    "Area(±${radius}px, $waitTag): ($x1, $y1) == $hex1 (±$tol)"
                }

                val resultIntent = Intent().apply {
                    putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", resultBundle)
                    putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
        layout.addView(saveBtn)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun createLabel(txt: String) = TextView(this).apply {
        text = txt
        setTextColor(Color.LTGRAY)
        setPadding(0, 20, 0, 6)
    }

    private fun createInput(hintStr: String, type: Int, initial: String) = EditText(this).apply {
        hint = hintStr
        inputType = type
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E1E"))
            cornerRadius = 12f
            setStroke(2, Color.parseColor("#444444"))
        }
        background = bg
        setPadding(24, 20, 24, 20)
    }
}
