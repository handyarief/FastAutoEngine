
package com.fastauto.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

import java.util.Locale

class PluginConfigActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "fast_auto_prefs"

        private const val EXTRA_BUNDLE =
            "com.twofortyfouram.locale.intent.extra.BUNDLE"

        private const val EXTRA_BLURB =
            "com.twofortyfouram.locale.intent.extra.BLURB"

        private const val MAX_TOLERANCE = 100
        private const val MAX_TIMEOUT_MS = 8000L
    }

    // ==================================================
    // DISPLAY SIZE
    // ==================================================

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    /*
     * Utamakan dimensi frame MediaProjection.
     * Jika belum aktif, gunakan dimensi layar perangkat.
     */
    private fun getReferenceScreenSize(): Pair<Int, Int> {

        val frame = ScreenCaptureService.getFrameInfo()

        if (
            frame != null &&
            frame.width > 0 &&
            frame.height > 0
        ) {
            return Pair(frame.width, frame.height)
        }

        val metrics = DisplayMetrics()

        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        return Pair(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    // ==================================================
    // HEX VALIDATION
    // ==================================================

    /*
     * Menerima:
     * #RRGGBB
     * RRGGBB
     * #AARRGGBB
     *
     * Semua hasil dinormalisasi menjadi #RRGGBB.
     */
    private fun normalizeHex(raw: String): String? {

        val parsed =
            ScreenCaptureService.parseHexToIntOrNull(
                raw.trim()
            ) ?: return null

        return String.format(
            Locale.US,
            "#%06X",
            parsed and 0xFFFFFF
        )
    }

    // ==================================================
    // COORDINATE VALIDATION
    // ==================================================

    private fun readCoordinate(
        input: EditText,
        label: String,
        maxSize: Int
    ): Int? {

        val value = input.text
            .toString()
            .trim()
            .toIntOrNull()

        if (value == null) {

            input.error = "$label harus berupa angka"

            input.requestFocus()

            return null
        }

        if (value < 0 || value >= maxSize) {

            input.error =
                "$label harus antara 0 sampai ${maxSize - 1}"

            input.requestFocus()

            return null
        }

        input.error = null

        return value
    }

    private fun readHex(
        input: EditText,
        label: String
    ): String? {

        val value = normalizeHex(
            input.text.toString()
        )

        if (value == null) {

            input.error =
                "$label tidak valid. Gunakan #RRGGBB"

            input.requestFocus()

            return null
        }

        input.error = null

        return value
    }

    // ==================================================
    // UI HELPERS
    // ==================================================

    private fun createLabel(txt: String): TextView {

        return TextView(this).apply {

            text = txt

            setTextColor(Color.LTGRAY)

            setTextSize(14f)

            setPadding(
                0,
                dp(18),
                0,
                dp(6)
            )
        }
    }

    private fun createInput(
        hintStr: String,
        type: Int,
        initial: String
    ): EditText {

        return EditText(this).apply {

            hint = hintStr

            inputType = type

            setSingleLine(true)

            setText(initial)

            setTextColor(Color.WHITE)

            setHintTextColor(Color.GRAY)

            textSize = 15f

            val bg = GradientDrawable().apply {

                setColor(
                    Color.parseColor("#1E1E1E")
                )

                cornerRadius = dp(10).toFloat()

                setStroke(
                    dp(1).coerceAtLeast(1),
                    Color.parseColor("#444444")
                )
            }

            background = bg

            setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
            )
        }
    }

    private fun createSpinner(
        items: Array<String>,
        selected: Int
    ): Spinner {

        return Spinner(this).apply {

            adapter = ArrayAdapter(
                this@PluginConfigActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )

            setBackgroundColor(
                Color.parseColor("#2A2A2A")
            )

            setSelection(
                selected.coerceIn(0, items.lastIndex)
            )
        }
    }

    private fun updatePickButton(
        button: Button,
        number: Int,
        x: Int,
        y: Int,
        hex: String
    ) {

        button.text =
            "🎯 Ambil Bidik Warna $number: ($x, $y) $hex"
    }

    private fun showValidationError(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    // ==================================================
    // ACTIVITY
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val oldBundle = intent.getBundleExtra(
            EXTRA_BUNDLE
        )

        // ----------------------------------------------
        // LOAD SAVED COLOR 1
        // ----------------------------------------------

        val lastX1 = prefs.getInt(
            "picked_x",
            540
        )

        val lastY1 = prefs.getInt(
            "picked_y",
            1200
        )

        val lastHex1 = prefs.getString(
            "picked_hex",
            "#FF0000"
        ) ?: "#FF0000"

        // ----------------------------------------------
        // LOAD SAVED COLOR 2
        // ----------------------------------------------

        val lastX2 = prefs.getInt(
            "picked_x2",
            540
        )

        val lastY2 = prefs.getInt(
            "picked_y2",
            1200
        )

        val lastHex2 = prefs.getString(
            "picked_hex2",
            "#00FF00"
        ) ?: "#00FF00"

        // ----------------------------------------------
        // SCREEN SIZE
        // ----------------------------------------------

        val screenSize = getReferenceScreenSize()

        val screenWidth = screenSize.first
        val screenHeight = screenSize.second

        // ----------------------------------------------
        // MAIN LAYOUT
        // ----------------------------------------------

        val scroll = ScrollView(this).apply {

            setBackgroundColor(
                Color.parseColor("#121212")
            )

            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(20),
                dp(24),
                dp(20),
                dp(28)
            )
        }

        // ----------------------------------------------
        // TITLE
        // ----------------------------------------------

        val title = TextView(this).apply {

            text = "⚡ Konfigurasi Plug-in Deteksi Warna"

            textSize = 21f

            setTextColor(
                Color.parseColor("#00E676")
            )

            setPadding(
                0,
                0,
                0,
                dp(14)
            )
        }

        layout.addView(title)

        val description = TextView(this).apply {

            text =
                "Atur koordinat, target warna, toleransi, " +
                "dan mode pemeriksaan untuk Automate."

            textSize = 13f

            setTextColor(Color.LTGRAY)
        }

        layout.addView(description)

        val screenInfo = TextView(this).apply {

            text =
                "Dimensi acuan: ${screenWidth} × ${screenHeight} px"

            textSize = 12f

            setTextColor(
                Color.parseColor("#80CBC4")
            )

            setPadding(
                0,
                dp(12),
                0,
                dp(8)
            )
        }

        layout.addView(screenInfo)

        // ==================================================
        // MODE
        // ==================================================

        val modes = arrayOf(

            "0: Cek Instan (frame RAM terbaru)",

            "1: Tunggu Sampai Warna Muncul",

            "2: Cari Warna di Area Sekitar (±100 px)"
        )

        val modeSpinner = createSpinner(
            modes,
            oldBundle?.getInt("mode", 0) ?: 0
        )

        layout.addView(
            createLabel("Pilih Mode Kerja Plug-in:")
        )

        layout.addView(modeSpinner)

        // ==================================================
        // COLOR 1
        // ==================================================

        val oldX1 = oldBundle?.getInt(
            "x",
            lastX1
        ) ?: lastX1

        val oldY1 = oldBundle?.getInt(
            "y",
            lastY1
        ) ?: lastY1

        val oldHex1 = oldBundle?.getString(
            "hex",
            lastHex1
        ) ?: lastHex1

        val pickBtn1 = Button(this).apply {

            setBackgroundColor(
                Color.parseColor("#1E88E5")
            )

            setTextColor(Color.WHITE)

            textSize = 13f

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            lp.setMargins(
                0,
                dp(20),
                0,
                dp(8)
            )

            layoutParams = lp
        }

        updatePickButton(
            pickBtn1,
            1,
            lastX1,
            lastY1,
            lastHex1
        )

        layout.addView(pickBtn1)

        val x1Input = createInput(
            "Koordinat X1",
            InputType.TYPE_CLASS_NUMBER,
            oldX1.toString()
        )

        val y1Input = createInput(
            "Koordinat Y1",
            InputType.TYPE_CLASS_NUMBER,
            oldY1.toString()
        )

        val hex1Input = createInput(
            "Target Warna 1 Hex (#RRGGBB)",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            oldHex1
        )

        layout.addView(
            createLabel("Koordinat X1 (Warna 1):")
        )

        layout.addView(x1Input)

        layout.addView(
            createLabel("Koordinat Y1 (Warna 1):")
        )

        layout.addView(y1Input)

        layout.addView(
            createLabel("Target Warna 1 Hex:")
        )

        layout.addView(hex1Input)

        pickBtn1.setOnClickListener {

            val p = getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

            val x = p.getInt("picked_x", 540)
            val y = p.getInt("picked_y", 1200)

            val hex = p.getString(
                "picked_hex",
                "#FF0000"
            ) ?: "#FF0000"

            x1Input.setText(x.toString())
            y1Input.setText(y.toString())
            hex1Input.setText(hex)

            updatePickButton(
                pickBtn1,
                1,
                x,
                y,
                hex
            )
        }

        // ==================================================
        // COLOR 2
        // ==================================================

        val useSecondColorCheck = CheckBox(this).apply {

            text =
                "Aktifkan Target Warna 2 (Koordinat & Warna Ke-2)"

            setTextColor(
                Color.parseColor("#00E676")
            )

            textSize = 14f

            isChecked = oldBundle?.getBoolean(
                "useColor2",
                false
            ) ?: false

            setPadding(
                0,
                dp(22),
                0,
                dp(8)
            )
        }

        layout.addView(useSecondColorCheck)

        val pickBtn2 = Button(this).apply {

            setBackgroundColor(
                Color.parseColor("#8E24AA")
            )

            setTextColor(Color.WHITE)

            textSize = 13f
        }

        updatePickButton(
            pickBtn2,
            2,
            lastX2,
            lastY2,
            lastHex2
        )

        layout.addView(pickBtn2)

        val oldX2 = oldBundle?.getInt(
            "x2",
            lastX2
        ) ?: lastX2

        val oldY2 = oldBundle?.getInt(
            "y2",
            lastY2
        ) ?: lastY2

        val oldHex2 = oldBundle?.getString(
            "hex2",
            lastHex2
        ) ?: lastHex2

        val x2Input = createInput(
            "Koordinat X2",
            InputType.TYPE_CLASS_NUMBER,
            oldX2.toString()
        )

        val y2Input = createInput(
            "Koordinat Y2",
            InputType.TYPE_CLASS_NUMBER,
            oldY2.toString()
        )

        val hex2Input = createInput(
            "Target Warna 2 Hex (#RRGGBB)",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            oldHex2
        )

        pickBtn2.setOnClickListener {

            val p = getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

            val x = p.getInt("picked_x2", 540)
            val y = p.getInt("picked_y2", 1200)

            val hex = p.getString(
                "picked_hex2",
                "#00FF00"
            ) ?: "#00FF00"

            x2Input.setText(x.toString())
            y2Input.setText(y.toString())
            hex2Input.setText(hex)

            useSecondColorCheck.isChecked = true

            updatePickButton(
                pickBtn2,
                2,
                x,
                y,
                hex
            )
        }

        layout.addView(
            createLabel("Logika Pengecekan Dua Warna:")
        )

        val logicModes = arrayOf(

            "0: DAN (AND) - Kedua warna harus cocok",

            "1: ATAU (OR) - Salah satu warna cocok"
        )

        val logicSpinner = createSpinner(
            logicModes,
            oldBundle?.getInt("colorLogic", 0) ?: 0
        )

        layout.addView(logicSpinner)

        layout.addView(
            createLabel("Koordinat X2 (Warna 2):")
        )

        layout.addView(x2Input)

        layout.addView(
            createLabel("Koordinat Y2 (Warna 2):")
        )

        layout.addView(y2Input)

        layout.addView(
            createLabel("Target Warna 2 Hex:")
        )

        layout.addView(hex2Input)

        // ==================================================
        // TOLERANCE
        // ==================================================

        val oldTolerance = oldBundle?.getInt(
            "tolerance",
            20
        ) ?: 20

        val tolInput = createInput(
            "Toleransi (0 - 100)",
            InputType.TYPE_CLASS_NUMBER,
            oldTolerance.toString()
        )

        layout.addView(
            createLabel(
                "Toleransi Kemiripan Warna (0–100):"
            )
        )

        layout.addView(tolInput)

        val toleranceInfo = TextView(this).apply {

            text =
                "Nilai 0 = warna harus sama persis. " +
                "Semakin besar toleransi, semakin longgar pencocokan RGB."

            textSize = 12f

            setTextColor(Color.GRAY)

            setPadding(
                0,
                dp(5),
                0,
                dp(4)
            )
        }

        layout.addView(toleranceInfo)

        // ==================================================
        // TIMEOUT
        // ==================================================

        val oldTimeout = oldBundle?.getLong(
            "timeout",
            1000L
        ) ?: 1000L

        val timeoutInput = createInput(
            "Timeout (1 - 8000 ms)",
            InputType.TYPE_CLASS_NUMBER,
            oldTimeout.toString()
        )

        layout.addView(
            createLabel(
                "Batas Waktu Tunggu (ms) - Khusus Mode 1:"
            )
        )

        layout.addView(timeoutInput)

        val timeoutInfo = TextView(this).apply {

            text =
                "Batas maksimum 8.000 ms. " +
                "Jika warna tidak ditemukan sampai batas waktu, " +
                "hasil kondisi adalah NO."

            textSize = 12f

            setTextColor(Color.GRAY)

            setPadding(
                0,
                dp(5),
                0,
                dp(4)
            )
        }

        layout.addView(timeoutInfo)

        // ==================================================
        // SAVE BUTTON
        // ==================================================

        val saveBtn = Button(this).apply {

            text = "✅ SIMPAN KE BLOK AUTOMATE"

            setBackgroundColor(
                Color.parseColor("#43A047")
            )

            setTextColor(Color.WHITE)

            textSize = 15f

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            lp.setMargins(
                0,
                dp(32),
                0,
                dp(8)
            )

            layoutParams = lp

            setOnClickListener {

                // ------------------------------------------
                // COORDINATES
                // ------------------------------------------

                val x1 = readCoordinate(
                    x1Input,
                    "X1",
                    screenWidth
                ) ?: return@setOnClickListener

                val y1 = readCoordinate(
                    y1Input,
                    "Y1",
                    screenHeight
                ) ?: return@setOnClickListener

                val x2 = readCoordinate(
                    x2Input,
                    "X2",
                    screenWidth
                ) ?: return@setOnClickListener

                val y2 = readCoordinate(
                    y2Input,
                    "Y2",
                    screenHeight
                ) ?: return@setOnClickListener

                // ------------------------------------------
                // HEX COLORS
                // ------------------------------------------

                val hex1 = readHex(
                    hex1Input,
                    "Warna 1"
                ) ?: return@setOnClickListener

                val hex2 = readHex(
                    hex2Input,
                    "Warna 2"
                ) ?: return@setOnClickListener

                // ------------------------------------------
                // OTHER SETTINGS
                // ------------------------------------------

                val tolerance =
                    tolInput.text.toString()
                        .trim()
                        .toIntOrNull()

                if (
                    tolerance == null ||
                    tolerance !in 0..MAX_TOLERANCE
                ) {

                    tolInput.error =
                        "Toleransi harus 0 sampai $MAX_TOLERANCE"

                    tolInput.requestFocus()

                    return@setOnClickListener
                }

                val timeout =
                    timeoutInput.text.toString()
                        .trim()
                        .toLongOrNull()

                if (
                    timeout == null ||
                    timeout !in 1L..MAX_TIMEOUT_MS
                ) {

                    timeoutInput.error =
                        "Timeout harus 1 sampai $MAX_TIMEOUT_MS ms"

                    timeoutInput.requestFocus()

                    return@setOnClickListener
                }

                // ------------------------------------------
                // SPINNER SETTINGS
                // ------------------------------------------

                val mode = modeSpinner.selectedItemPosition
                    .coerceIn(0, 2)

                val useColor2 =
                    useSecondColorCheck.isChecked

                val colorLogic =
                    logicSpinner.selectedItemPosition
                        .coerceIn(0, 1)

                // ------------------------------------------
                // BUILD ORIGINAL BUNDLE CONTRACT
                // ------------------------------------------

                val resultBundle = Bundle().apply {

                    putInt("mode", mode)

                    putInt("x", x1)
                    putInt("y", y1)
                    putString("hex", hex1)

                    putBoolean(
                        "useColor2",
                        useColor2
                    )

                    putInt(
                        "colorLogic",
                        colorLogic
                    )

                    putInt("x2", x2)
                    putInt("y2", y2)
                    putString("hex2", hex2)

                    putInt(
                        "tolerance",
                        tolerance
                    )

                    putLong(
                        "timeout",
                        timeout
                    )
                }

                // ------------------------------------------
                // DESCRIPTION / BLURB
                // ------------------------------------------

                val modeShort = when (mode) {

                    0 -> "Cek Instan"

                    1 -> "Tunggu Warna"

                    else -> "Cari Area"
                }

                val logicStr = if (colorLogic == 0) {
                    "AND"
                } else {
                    "OR"
                }

                val blurb = if (useColor2) {

                    "$modeShort: " +
                    "W1($x1,$y1)=$hex1 " +
                    "$logicStr " +
                    "W2($x2,$y2)=$hex2 " +
                    "(±$tolerance)"

                } else {

                    "$modeShort " +
                    "($x1,$y1) == $hex1 " +
                    "(±$tolerance)"
                }

                // ------------------------------------------
                // RETURN RESULT TO AUTOMATE
                // ------------------------------------------

                val resultIntent = Intent().apply {

                    putExtra(
                        EXTRA_BUNDLE,
                        resultBundle
                    )

                    putExtra(
                        EXTRA_BLURB,
                        blurb
                    )
                }

                setResult(
                    Activity.RESULT_OK,
                    resultIntent
                )

                finish()
            }
        }

        layout.addView(saveBtn)

        // ==================================================
        // FOOTER
        // ==================================================

        val footer = TextView(this).apply {

            text =
                "FastAuto Color Engine\n" +
                "Konfigurasi pemeriksaan warna untuk Automate."

            gravity = Gravity.CENTER

            textSize = 11f

            setTextColor(Color.GRAY)

            setPadding(
                0,
                dp(12),
                0,
                dp(8)
            )
        }

        layout.addView(footer)

        scroll.addView(layout)

        setContentView(scroll)
    }
}
