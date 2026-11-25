package com.fastauto.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PluginConfigActivity : Activity() {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        val lastX1 = prefs.getInt("picked_x", 540).coerceAtLeast(0)
        val lastY1 = prefs.getInt("picked_y", 1200).coerceAtLeast(0)
        val lastHex1 = normalizeHexOrDefault(prefs.getString("picked_hex", "#FF0000"), "#FF0000")

        val lastX2 = prefs.getInt("picked_x2", 540).coerceAtLeast(0)
        val lastY2 = prefs.getInt("picked_y2", 1200).coerceAtLeast(0)
        val lastHex2 = normalizeHexOrDefault(prefs.getString("picked_hex2", "#00FF00"), "#00FF00")

        val oldBundle = try {
            intent.getBundleExtra(PluginFireReceiver.EXTRA_BUNDLE)
        } catch (_: Throwable) {
            null
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
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
            text = "⚡ Konfigurasi Pemindai Area (1 atau 2 Warna)"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, dp(4))
        }
        layout.addView(title)

        val subInfo = TextView(this).apply {
            text = "Memindai kotak area (± radius) secara On-Demand (True-Idle saat tidak dipanggil)."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(subInfo)

        // ================= TARGET WARNA 1 =================
        val pickBtn1 = Button(this).apply {
            text = "🎯 Ambil Bidik Area 1: ($lastX1, $lastY1) $lastHex1"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(6), 0, dp(4))
            layoutParams = lp
        }
        layout.addView(pickBtn1)

        val x1Input = createInput(
            "Pusat X1",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("x", lastX1) ?: lastX1).coerceAtLeast(0).toString()
        )
        val y1Input = createInput(
            "Pusat Y1",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("y", lastY1) ?: lastY1).coerceAtLeast(0).toString()
        )
        val hex1Input = createInput(
            "Target Warna 1 Hex (#RRGGBB)",
            InputType.TYPE_CLASS_TEXT,
            normalizeHexOrDefault(oldBundle?.getString("hex", lastHex1), lastHex1)
        )

        pickBtn1.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            val px = p.getInt("picked_x", 540).coerceAtLeast(0)
            val py = p.getInt("picked_y", 1200).coerceAtLeast(0)
            val ph = normalizeHexOrDefault(p.getString("picked_hex", "#FF0000"), "#FF0000")
            x1Input.setText(px.toString())
            y1Input.setText(py.toString())
            hex1Input.setText(ph)
            hex1Input.error = null
        }

        layout.addView(createLabel("Koordinat Pusat X1 (Area 1):"))
        layout.addView(x1Input)
        layout.addView(createLabel("Koordinat Pusat Y1 (Area 1):"))
        layout.addView(y1Input)
        layout.addView(createLabel("Target Warna 1 Hex (Contoh #FF0000):"))
        layout.addView(hex1Input)

        // ================= TARGET WARNA 2 (OPSIONAL) =================
        val initialUseColor2 = oldBundle?.getBoolean("useColor2", false) ?: false
        val useSecondColorCheck = CheckBox(this).apply {
            text = "Aktifkan Target Area Warna 2 (Mode 2 Warna)"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 15f
            isChecked = initialUseColor2
            setPadding(0, dp(14), 0, dp(4))
        }
        layout.addView(useSecondColorCheck)

        val secondColorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialUseColor2) View.VISIBLE else View.GONE
        }

        val pickBtn2 = Button(this).apply {
            text = "🎯 Ambil Bidik Area 2: ($lastX2, $lastY2) $lastHex2"
            setBackgroundColor(Color.parseColor("#8E24AA"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
        secondColorContainer.addView(pickBtn2)

        val logicModes = arrayOf(
            "0: DAN (AND) -> Area 1 & Area 2 Harus Ditemukan Semua",
            "1: ATAU (OR) -> Salah Satu Area Ditemukan Saja Cukup"
        )
        val spinnerAdapter = object : ArrayAdapter<String>(
            this@PluginConfigActivity,
            android.R.layout.simple_spinner_item,
            logicModes
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setPadding(dp(10), dp(10), dp(10), dp(10))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setBackgroundColor(Color.parseColor("#2A2A2A"))
                v.setPadding(dp(12), dp(12), dp(12), dp(12))
                return v
            }
        }
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val savedLogic = oldBundle?.getInt("colorLogic", ScreenCaptureService.LOGIC_AND)
            ?: ScreenCaptureService.LOGIC_AND
        val logicSpinner = Spinner(this).apply {
            adapter = spinnerAdapter
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#555555"))
            }
            setSelection(if (savedLogic == ScreenCaptureService.LOGIC_OR) 1 else 0)
        }

        val x2Input = createInput(
            "Pusat X2",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("x2", lastX2) ?: lastX2).coerceAtLeast(0).toString()
        )
        val y2Input = createInput(
            "Pusat Y2",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("y2", lastY2) ?: lastY2).coerceAtLeast(0).toString()
        )
        val hex2Input = createInput(
            "Target Warna 2 Hex (#RRGGBB)",
            InputType.TYPE_CLASS_TEXT,
            normalizeHexOrDefault(oldBundle?.getString("hex2", lastHex2), lastHex2)
        )

        pickBtn2.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            val px2 = p.getInt("picked_x2", 540).coerceAtLeast(0)
            val py2 = p.getInt("picked_y2", 1200).coerceAtLeast(0)
            val ph2 = normalizeHexOrDefault(p.getString("picked_hex2", "#00FF00"), "#00FF00")
            x2Input.setText(px2.toString())
            y2Input.setText(py2.toString())
            hex2Input.setText(ph2)
            hex2Input.error = null
            useSecondColorCheck.isChecked = true
        }

        useSecondColorCheck.setOnCheckedChangeListener { _, isChecked ->
            secondColorContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                hex2Input.error = null
            }
        }

        secondColorContainer.addView(createLabel("Logika Pencarian 2 Area:"))
        secondColorContainer.addView(logicSpinner)
        secondColorContainer.addView(createLabel("Koordinat Pusat X2 (Area 2):"))
        secondColorContainer.addView(x2Input)
        secondColorContainer.addView(createLabel("Koordinat Pusat Y2 (Area 2):"))
        secondColorContainer.addView(y2Input)
        secondColorContainer.addView(createLabel("Target Warna 2 Hex (Contoh #00FF00):"))
        secondColorContainer.addView(hex2Input)

        layout.addView(secondColorContainer)

        // ================= PENGATURAN AREA & KECEPATAN =================
        val radiusInput = createInput(
            "Radius Area Piksel (Default 100)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("radius", 100) ?: 100).coerceIn(1, 500).toString()
        )
        val stepInput = createInput(
            "Kerapatan Langkah Piksel (1 = Sangat Rapat, 4 = Cepat)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("step", 4) ?: 4).coerceIn(1, 20).toString()
        )
        val tolInput = createInput(
            "Toleransi Kemiripan Warna (0 - 255)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("tolerance", 20) ?: 20).coerceIn(0, 255).toString()
        )
        val timeoutInput = createInput(
            "0 = Cek Sekali | >0 = Tunggu Maks (ms, maks 5000)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getLong("timeout", 0L) ?: 0L).coerceIn(0L, 5000L).toString()
        )

        layout.addView(createLabel("Radius Area Pencarian (± piksel dari titik pusat):"))
        layout.addView(radiusInput)
        layout.addView(createLabel("Langkah Pemindaian / Step (2 - 4 disarankan):"))
        layout.addView(stepInput)
        layout.addView(createLabel("Toleransi Warna (15 - 25 disarankan):"))
        layout.addView(tolInput)
        layout.addView(createLabel("Batas Tunggu Maksimal ms (0 untuk sekali cek):"))
        layout.addView(timeoutInput)

        val saveBtn = Button(this).apply {
            text = "✅ SIMPAN KE BLOK AUTOMATE"
            setBackgroundColor(Color.parseColor("#43A047"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(18), 0, dp(8))
            layoutParams = lp

            setOnClickListener {
                val x1 = (x1Input.text.toString().toIntOrNull() ?: 540).coerceAtLeast(0)
                val y1 = (y1Input.text.toString().toIntOrNull() ?: 1200).coerceAtLeast(0)
                val rawHex1 = hex1Input.text.toString().trim()
                val rgb1 = ScreenCaptureService.parseHexToIntOrNull(rawHex1)
                if (rgb1 == null) {
                    hex1Input.error = "Format Hex harus #RRGGBB (contoh: #FF0000)"
                    hex1Input.requestFocus()
                    return@setOnClickListener
                }
                hex1Input.error = null
                val hex1 = String.format("#%06X", rgb1)

                val useColor2 = useSecondColorCheck.isChecked
                val colorLogic = if (logicSpinner.selectedItemPosition == 1) {
                    ScreenCaptureService.LOGIC_OR
                } else {
                    ScreenCaptureService.LOGIC_AND
                }

                var x2 = 0
                var y2 = 0
                var hex2 = ""
                var rgb2 = 0

                if (useColor2) {
                    x2 = (x2Input.text.toString().toIntOrNull() ?: 540).coerceAtLeast(0)
                    y2 = (y2Input.text.toString().toIntOrNull() ?: 1200).coerceAtLeast(0)
                    val rawHex2 = hex2Input.text.toString().trim()
                    val parsed2 = ScreenCaptureService.parseHexToIntOrNull(rawHex2)
                    if (parsed2 == null) {
                        hex2Input.error = "Format Hex harus #RRGGBB (contoh: #00FF00)"
                        hex2Input.requestFocus()
                        return@setOnClickListener
                    }
                    hex2Input.error = null
                    rgb2 = parsed2
                    hex2 = String.format("#%06X", rgb2)
                }

                val radius = (radiusInput.text.toString().toIntOrNull() ?: 100).coerceIn(1, 500)
                val step = (stepInput.text.toString().toIntOrNull() ?: 4).coerceIn(1, 20)
                val tol = (tolInput.text.toString().toIntOrNull() ?: 20).coerceIn(0, 255)
                val timeout = (timeoutInput.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 5000L)

                val resultBundle = Bundle().apply {
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

                val logicStr = if (colorLogic == ScreenCaptureService.LOGIC_AND) "AND" else "OR"
                val waitTag = if (timeout > 0L) "Wait ${timeout}ms" else "Instant"
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

    private fun normalizeHexOrDefault(raw: String?, fallback: String): String {
        val parsed = ScreenCaptureService.parseHexToIntOrNull(raw)
        return if (parsed != null) String.format("#%06X", parsed) else fallback
    }

    private fun createLabel(txt: String) = TextView(this).apply {
        text = txt
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(10), 0, dp(3))
    }

    private fun createInput(hintStr: String, type: Int, initial: String) = EditText(this).apply {
        hint = hintStr
        inputType = type
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E1E"))
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#444444"))
        }
        background = bg
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
}
