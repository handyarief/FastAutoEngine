package com.fastauto.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.*

class PluginConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        val lastX = prefs.getInt("picked_x", 540)
        val lastY = prefs.getInt("picked_y", 1200)
        val lastHex = prefs.getString("picked_hex", "#FF0000") ?: "#FF0000"

        // Ambil konfigurasi lama jika user mengedit ulang blok di Automate
        val oldBundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "⚡ Konfigurasi Plug-in Deteksi Warna"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val pickBtn = Button(this).apply {
            text = "🎯 Ambil Hasil Bidik Terakhir: ($lastX, $lastY) $lastHex"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
        }
        layout.addView(pickBtn)

        val modes = arrayOf(
            "0: Cek Instan 1ms (Cabang YES / NO di Automate)",
            "1: Tunggu Sampai Warna Muncul (Zero-Delay Wait)",
            "2: Cari Warna di Area Sekitar (±100px) & Auto-Tap"
        )
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PluginConfigActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setSelection(oldBundle?.getInt("mode", 0) ?: 0)
        }

        val xInput = createInput("Koordinat X", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("x", lastX) ?: lastX).toString())
        val yInput = createInput("Koordinat Y", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("y", lastY) ?: lastY).toString())
        val hexInput = createInput("Kode Warna Hex (#RRGGBB)", InputType.TYPE_CLASS_TEXT, oldBundle?.getString("hex", lastHex) ?: lastHex)
        val tolInput = createInput("Toleransi Kemiripan Warna (0 - 100)", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getInt("tolerance", 20) ?: 20).toString())
        val timeoutInput = createInput("Batas Waktu Tunggu ms (Khusus Mode 1, misal 5000)", InputType.TYPE_CLASS_NUMBER, (oldBundle?.getLong("timeout", 5000L) ?: 5000L).toString())

        val autoTapCheck = CheckBox(this).apply {
            text = "Langsung Auto-Tap Instan jika warna cocok!"
            setTextColor(Color.WHITE)
            isChecked = oldBundle?.getBoolean("autoTap", false) ?: false
        }

        pickBtn.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            xInput.setText(p.getInt("picked_x", 540).toString())
            yInput.setText(p.getInt("picked_y", 1200).toString())
            hexInput.setText(p.getString("picked_hex", "#FF0000"))
        }

        layout.addView(createLabel("Pilih Mode Kerja Plug-in:"))
        layout.addView(modeSpinner)
        layout.addView(createLabel("Koordinat X:"))
        layout.addView(xInput)
        layout.addView(createLabel("Koordinat Y:"))
        layout.addView(yInput)
        layout.addView(createLabel("Target Warna Hex (Contoh #FF0000):"))
        layout.addView(hexInput)
        layout.addView(createLabel("Toleransi Warna (15-25 disarankan):"))
        layout.addView(tolInput)
        layout.addView(createLabel("Timeout Tunggu (ms) untuk Mode 1:"))
        layout.addView(timeoutInput)
        layout.addView(autoTapCheck)

        val saveBtn = Button(this).apply {
            text = "✅ SIMPAN KE BLOK AUTOMATE"
            setBackgroundColor(Color.parseColor("#43A047"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 40, 0, 0)
            layoutParams = lp

            setOnClickListener {
                val mode = modeSpinner.selectedItemPosition
                val x = xInput.text.toString().toIntOrNull() ?: 500
                val y = yInput.text.toString().toIntOrNull() ?: 500
                val hex = hexInput.text.toString().trim().ifEmpty { "#FF0000" }
                val tol = tolInput.text.toString().toIntOrNull() ?: 20
                val timeout = timeoutInput.text.toString().toLongOrNull() ?: 5000L
                val autoTap = autoTapCheck.isChecked

                val resultBundle = Bundle().apply {
                    putInt("mode", mode)
                    putInt("x", x)
                    putInt("y", y)
                    putString("hex", hex)
                    putInt("tolerance", tol)
                    putLong("timeout", timeout)
                    putBoolean("autoTap", autoTap)
                }

                val modeShort = when (mode) {
                    0 -> "Cek Instan"
                    1 -> "Tunggu Warna"
                    else -> "Scan Area"
                }
                val blurb = "$modeShort ($x, $y) == $hex (±$tol) ${if (autoTap) "[+AutoTap]" else ""}"

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
