package com.fastauto.engine

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PluginConfigActivity : AppCompatActivity() {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        val lastX1 = prefs.getInt("picked_x", 540).coerceAtLeast(0)
        val lastY1 = prefs.getInt("picked_y", 1200).coerceAtLeast(0)
        val lastHex1 = normalizeHexOrDefault(prefs.getString("picked_hex", "#FF0000"), "#FF0000")

        val lastX2 = prefs.getInt("picked_x2", lastX1).coerceAtLeast(0)
        val lastY2 = prefs.getInt("picked_y2", lastY1).coerceAtLeast(0)
        val lastHex2 = normalizeHexOrDefault(prefs.getString("picked_hex2", "#00FF00"), "#00FF00")

        val oldBundle = try {
            @Suppress("DEPRECATION")
            intent.getBundleExtra(PluginFireReceiver.EXTRA_BUNDLE)
        } catch (_: Throwable) {
            null
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeBars = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPad = maxOf(sysBars.bottom, imeBars.bottom) + dp(16)
            v.setPadding(
                dp(16) + sysBars.left,
                dp(16) + sysBars.top,
                dp(16) + sysBars.right,
                bottomPad
            )
            insets
        }

        layout.addView(TextView(this).apply {
            text = "⚡ Konfigurasi Pemindai Area (One-Shot Opsi A)"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, dp(4))
        })

        layout.addView(TextView(this).apply {
            text = "Pastikan blok Automate diset ke Proceed: Immediately. True-Idle saat tidak dipanggil."
            textSize = 12.5f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        })

        // ================= PILIHAN 3 MODE WARNA =================
        val modeLabels = arrayOf(
            "Mode 2: Satu Warna (Warna 1 Saja)",
            "Mode 1: Dua Warna Sekaligus (AND / OR)",
            "Mode 3: Pilih Salah Satu Warna (Warna 1 atau Warna 2)"
        )
        val modeAdapter = createDarkSpinnerAdapter(modeLabels)
        val savedMode = oldBundle?.getInt("colorMode", ScreenCaptureService.COLOR_MODE_SINGLE)
            ?: ScreenCaptureService.COLOR_MODE_SINGLE
        val modeSpinner = Spinner(this).apply {
            adapter = modeAdapter
            background = spinnerBg()
            setSelection(
                when (savedMode) {
                    ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> 1
                    ScreenCaptureService.COLOR_MODE_PICK_ONE -> 2
                    else -> 0
                }
            )
        }

        val pickOneLabels = arrayOf(
            "Gunakan Warna 1 Saja (Warna 2 Diabaikan)",
            "Gunakan Warna 2 Saja (Warna 1 Diabaikan)"
        )
        val pickOneAdapter = createDarkSpinnerAdapter(pickOneLabels)
        val savedPickOne = oldBundle?.getInt("pickOneTarget", ScreenCaptureService.PICK_ONE_COLOR_1)
            ?: ScreenCaptureService.PICK_ONE_COLOR_1
        val pickOneSpinner = Spinner(this).apply {
            adapter = pickOneAdapter
            background = spinnerBg()
            setSelection(if (savedPickOne == ScreenCaptureService.PICK_ONE_COLOR_2) 1 else 0)
        }

        val logicLabels = arrayOf(
            "Logika AND (Warna 1 & Warna 2 Keduanya Cocok)",
            "Logika OR (Salah Satu Warna Cocok)"
        )
        val logicAdapter = createDarkSpinnerAdapter(logicLabels)
        val savedLogic = oldBundle?.getInt("colorLogic", ScreenCaptureService.LOGIC_AND)
            ?: ScreenCaptureService.LOGIC_AND
        val logicSpinner = Spinner(this).apply {
            adapter = logicAdapter
            background = spinnerBg()
            setSelection(if (savedLogic == ScreenCaptureService.LOGIC_OR) 1 else 0)
        }

        val sameCoordCheck = CheckBox(this).apply {
            text = "Gunakan Koordinat Area Sama dengan Warna 1"
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 13.5f
            isChecked = oldBundle?.getBoolean("sameCoord2", true) ?: true
            setPadding(0, dp(8), 0, dp(4))
        }

        layout.addView(createLabel("Mode Pemilihan Warna:"))
        layout.addView(modeSpinner)

        val pickOneContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createLabel("Target Warna Terpilih (Mode 3):"))
            addView(pickOneSpinner)
        }
        layout.addView(pickOneContainer)

        // ================= BLOK WARNA 1 =================
        val color1Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val pickBtn1 = Button(this).apply {
            text = "🎯 Ambil Bidik Warna 1: ($lastX1, $lastY1) $lastHex1"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            layoutParams = buttonParams(topMarginDp = 8)
        }
        color1Container.addView(pickBtn1)

        val initX1 = oldBundle?.getInt("x", lastX1)?.takeIf { it >= 0 } ?: lastX1
        val initY1 = oldBundle?.getInt("y", lastY1)?.takeIf { it >= 0 } ?: lastY1
        val initHex1 = normalizeHexOrDefault(oldBundle?.getString("hex"), lastHex1)

        val x1Input = createInput("X1", InputType.TYPE_CLASS_NUMBER, initX1.toString())
        val y1Input = createInput("Y1", InputType.TYPE_CLASS_NUMBER, initY1.toString())
        val hex1Input = createInput("#RRGGBB", InputType.TYPE_CLASS_TEXT, initHex1)

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

        color1Container.addView(createLabel("Koordinat Pusat X1:"))
        color1Container.addView(x1Input)
        color1Container.addView(createLabel("Koordinat Pusat Y1:"))
        color1Container.addView(y1Input)
        color1Container.addView(createLabel("Target Warna 1 Hex (#RRGGBB):"))
        color1Container.addView(hex1Input)
        layout.addView(color1Container)

        // ================= BLOK WARNA 2 =================
        val color2Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val logicContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createLabel("Logika Dua Warna (Mode 1):"))
            addView(logicSpinner)
            addView(sameCoordCheck)
        }
        color2Container.addView(logicContainer)

        val pickBtn2 = Button(this).apply {
            text = "🎯 Ambil Bidik Warna 2: ($lastX2, $lastY2) $lastHex2"
            setBackgroundColor(Color.parseColor("#8E24AA"))
            setTextColor(Color.WHITE)
            layoutParams = buttonParams(topMarginDp = 8)
        }
        color2Container.addView(pickBtn2)

        val initX2 = oldBundle?.getInt("x2", lastX2)?.takeIf { it >= 0 } ?: lastX2
        val initY2 = oldBundle?.getInt("y2", lastY2)?.takeIf { it >= 0 } ?: lastY2
        val initHex2 = normalizeHexOrDefault(oldBundle?.getString("hex2"), lastHex2)

        val coord2FieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val x2Input = createInput("X2", InputType.TYPE_CLASS_NUMBER, initX2.toString())
        val y2Input = createInput("Y2", InputType.TYPE_CLASS_NUMBER, initY2.toString())
        coord2FieldsContainer.addView(createLabel("Koordinat Pusat X2:"))
        coord2FieldsContainer.addView(x2Input)
        coord2FieldsContainer.addView(createLabel("Koordinat Pusat Y2:"))
        coord2FieldsContainer.addView(y2Input)
        color2Container.addView(coord2FieldsContainer)

        val hex2Input = createInput("#RRGGBB", InputType.TYPE_CLASS_TEXT, initHex2)
        color2Container.addView(createLabel("Target Warna 2 Hex (#RRGGBB):"))
        color2Container.addView(hex2Input)

        pickBtn2.setOnClickListener {
            val p = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
            val px2 = p.getInt("picked_x2", lastX2).coerceAtLeast(0)
            val py2 = p.getInt("picked_y2", lastY2).coerceAtLeast(0)
            val ph2 = normalizeHexOrDefault(p.getString("picked_hex2", "#00FF00"), "#00FF00")
            x2Input.setText(px2.toString())
            y2Input.setText(py2.toString())
            hex2Input.setText(ph2)
            hex2Input.error = null
        }

        layout.addView(color2Container)

        // ================= PENGATURAN AREA ROI & AKURASI =================
        val radiusInput = createInput(
            "Radius Area (± px, disarankan 10 - 120)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("radius", 40) ?: 40).coerceIn(1, 600).toString()
        )
        val stepInput = createInput(
            "Langkah Piksel (1 = Sangat Akurat, 2 = Seimbang)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("step", 2) ?: 2).coerceIn(1, 24).toString()
        )
        val tolInput = createInput(
            "Toleransi Warna (0 - 255, disarankan 15 - 30)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("tolerance", 20) ?: 20).coerceIn(0, 255).toString()
        )
        val minMatchesInput = createInput(
            "Jumlah Piksel Cocok Minimum (1 = 1 Piksel, >=3 = Anti-Noise)",
            InputType.TYPE_CLASS_NUMBER,
            (oldBundle?.getInt("minMatches", 1) ?: 1).coerceIn(1, 500).toString()
        )

        layout.addView(createLabel("Radius Area ROI (± piksel):"))
        layout.addView(radiusInput)
        layout.addView(createLabel("Langkah Pemindaian / Step:"))
        layout.addView(stepInput)
        layout.addView(createLabel("Toleransi Warna (0 - 255):"))
        layout.addView(tolInput)
        layout.addView(createLabel("Ambang Kecocokan Piksel Minimum:"))
        layout.addView(minMatchesInput)

        fun refreshVisibility() {
            val mode = when (modeSpinner.selectedItemPosition) {
                1 -> ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS
                2 -> ScreenCaptureService.COLOR_MODE_PICK_ONE
                else -> ScreenCaptureService.COLOR_MODE_SINGLE
            }
            val pickOne = if (pickOneSpinner.selectedItemPosition == 1) {
                ScreenCaptureService.PICK_ONE_COLOR_2
            } else {
                ScreenCaptureService.PICK_ONE_COLOR_1
            }

            pickOneContainer.visibility = if (mode == ScreenCaptureService.COLOR_MODE_PICK_ONE) View.VISIBLE else View.GONE

            val show1 = when (mode) {
                ScreenCaptureService.COLOR_MODE_SINGLE,
                ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> true
                ScreenCaptureService.COLOR_MODE_PICK_ONE -> pickOne == ScreenCaptureService.PICK_ONE_COLOR_1
                else -> true
            }
            val show2 = when (mode) {
                ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> true
                ScreenCaptureService.COLOR_MODE_PICK_ONE -> pickOne == ScreenCaptureService.PICK_ONE_COLOR_2
                else -> false
            }

            color1Container.visibility = if (show1) View.VISIBLE else View.GONE
            color2Container.visibility = if (show2) View.VISIBLE else View.GONE
            logicContainer.visibility = if (mode == ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS) View.VISIBLE else View.GONE
            coord2FieldsContainer.visibility = if (show2 && !(mode == ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS && sameCoordCheck.isChecked)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val visibilityListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshVisibility()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        modeSpinner.onItemSelectedListener = visibilityListener
        pickOneSpinner.onItemSelectedListener = visibilityListener
        sameCoordCheck.setOnCheckedChangeListener { _, _ -> refreshVisibility() }
        refreshVisibility()

        val saveBtn = Button(this).apply {
            text = "✅ SIMPAN KE BLOK AUTOMATE"
            setBackgroundColor(Color.parseColor("#43A047"))
            setTextColor(Color.WHITE)
            layoutParams = buttonParams(topMarginDp = 18)

            setOnClickListener {
                val mode = when (modeSpinner.selectedItemPosition) {
                    1 -> ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS
                    2 -> ScreenCaptureService.COLOR_MODE_PICK_ONE
                    else -> ScreenCaptureService.COLOR_MODE_SINGLE
                }
                val pickOne = if (pickOneSpinner.selectedItemPosition == 1) {
                    ScreenCaptureService.PICK_ONE_COLOR_2
                } else {
                    ScreenCaptureService.PICK_ONE_COLOR_1
                }
                val colorLogic = if (logicSpinner.selectedItemPosition == 1) {
                    ScreenCaptureService.LOGIC_OR
                } else {
                    ScreenCaptureService.LOGIC_AND
                }
                val sameCoord2 = sameCoordCheck.isChecked

                val use1 = (mode == ScreenCaptureService.COLOR_MODE_SINGLE) ||
                    (mode == ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS) ||
                    (mode == ScreenCaptureService.COLOR_MODE_PICK_ONE && pickOne == ScreenCaptureService.PICK_ONE_COLOR_1)
                val use2 = (mode == ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS) ||
                    (mode == ScreenCaptureService.COLOR_MODE_PICK_ONE && pickOne == ScreenCaptureService.PICK_ONE_COLOR_2)

                val x1 = (x1Input.text.toString().toIntOrNull() ?: lastX1).coerceAtLeast(0)
                val y1 = (y1Input.text.toString().toIntOrNull() ?: lastY1).coerceAtLeast(0)
                var rgb1 = ScreenCaptureService.parseHexToIntOrNull(hex1Input.text.toString().trim()) ?: 0
                var hex1 = if (rgb1 != 0) String.format("#%06X", rgb1) else normalizeHexOrDefault(hex1Input.text.toString().trim(), lastHex1)

                if (use1) {
                    val parsed1 = ScreenCaptureService.parseHexToIntOrNull(hex1Input.text.toString().trim())
                    if (parsed1 == null) {
                        hex1Input.error = "Format Hex harus #RRGGBB (contoh: #FF0000)"
                        hex1Input.requestFocus()
                        return@setOnClickListener
                    }
                    hex1Input.error = null
                    rgb1 = parsed1
                    hex1 = String.format("#%06X", rgb1)
                }

                var x2 = (x2Input.text.toString().toIntOrNull() ?: lastX2).coerceAtLeast(0)
                var y2 = (y2Input.text.toString().toIntOrNull() ?: lastY2).coerceAtLeast(0)
                var rgb2 = ScreenCaptureService.parseHexToIntOrNull(hex2Input.text.toString().trim()) ?: 0
                var hex2 = if (rgb2 != 0) String.format("#%06X", rgb2) else normalizeHexOrDefault(hex2Input.text.toString().trim(), lastHex2)

                if (use2) {
                    if (mode == ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS && sameCoord2 && use1) {
                        x2 = x1
                        y2 = y1
                    }
                    val parsed2 = ScreenCaptureService.parseHexToIntOrNull(hex2Input.text.toString().trim())
                    if (parsed2 == null) {
                        hex2Input.error = "Format Hex harus #RRGGBB (contoh: #00FF00)"
                        hex2Input.requestFocus()
                        return@setOnClickListener
                    }
                    hex2Input.error = null
                    rgb2 = parsed2
                    hex2 = String.format("#%06X", rgb2)
                }

                val radius = (radiusInput.text.toString().toIntOrNull() ?: 40).coerceIn(1, 600)
                val step = (stepInput.text.toString().toIntOrNull() ?: 2).coerceIn(1, 24)
                val tol = (tolInput.text.toString().toIntOrNull() ?: 20).coerceIn(0, 255)
                val minMatches = (minMatchesInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 500)

                val (refW, refH, refRot) = currentScreenReference()

                val resultBundle = Bundle().apply {
                    putInt("colorMode", mode)
                    putInt("pickOneTarget", pickOne)
                    putInt("colorLogic", colorLogic)
                    putBoolean("sameCoord2", sameCoord2)
                    putInt("x", x1)
                    putInt("y", y1)
                    putString("hex", hex1)
                    putInt("rgb1", rgb1)
                    putInt("x2", x2)
                    putInt("y2", y2)
                    putString("hex2", hex2)
                    putInt("rgb2", rgb2)
                    putInt("refWidth", refW)
                    putInt("refHeight", refH)
                    putInt("refRotation", refRot)
                    putInt("radius", radius)
                    putInt("step", step)
                    putInt("tolerance", tol)
                    putInt("minMatches", minMatches)
                }

                val blurb = when (mode) {
                    ScreenCaptureService.COLOR_MODE_DUAL_SIMULTANEOUS -> {
                        val logicTag = if (colorLogic == ScreenCaptureService.LOGIC_OR) "OR" else "AND"
                        "OneShot M1(±${radius}px): W1($x1,$y1)=$hex1 $logicTag W2($x2,$y2)=$hex2"
                    }
                    ScreenCaptureService.COLOR_MODE_PICK_ONE -> {
                        if (pickOne == ScreenCaptureService.PICK_ONE_COLOR_2) {
                            "OneShot M3(W2 Saja, ±${radius}px): ($x2,$y2) == $hex2"
                        } else {
                            "OneShot M3(W1 Saja, ±${radius}px): ($x1,$y1) == $hex1"
                        }
                    }
                    else -> "OneShot M2(±${radius}px): ($x1,$y1) == $hex1 (±$tol)"
                }

                val resultIntent = Intent().apply {
                    putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", resultBundle)
                    putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
                    putExtra(
                        PluginFireReceiver.EXTRA_RELEVANT_VARIABLES,
                        arrayOf(
                            "%fastauto_status\nStatus Hasil\nSATISFIED, UNSATISFIED, atau UNKNOWN",
                            "%fastauto_detail\nDetail Kode\nKode hasil atau alasan error",
                            "%fastauto_match_count\nJumlah Piksel Cocok\nJumlah piksel yang memenuhi toleransi"
                        )
                    )
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
        layout.addView(saveBtn)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun currentScreenReference(): Triple<Int, Int, Int> {
        if (ScreenCaptureService.screenWidth > 0 && ScreenCaptureService.screenHeight > 0) {
            return Triple(
                ScreenCaptureService.screenWidth,
                ScreenCaptureService.screenHeight,
                ScreenCaptureService.screenRotation
            )
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val rot = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: wm.defaultDisplay.rotation
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width().coerceAtLeast(1), b.height().coerceAtLeast(1), rot)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), rot)
        }
    }

    private fun createDarkSpinnerAdapter(items: Array<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
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
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun spinnerBg() = GradientDrawable().apply {
        setColor(Color.parseColor("#2A2A2A"))
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#555555"))
    }

    private fun buttonParams(topMarginDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(0, dp(topMarginDp), 0, dp(4))
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
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E1E"))
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#444444"))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
}
