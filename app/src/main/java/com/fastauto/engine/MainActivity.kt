package com.fastauto.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var blocksContainer: LinearLayout
    private var floatingView: View? = null
    private var crosshairView: View? = null
    private var lastPickedX = 540
    private var lastPickedY = 1200
    private var lastPickedHex = "#FF0000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedBlocks()
        setupUI()
    }

    private fun setupUI() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "⚡ FastAuto Engine"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(title)

        statusText = TextView(this).apply {
            text = "Status: Memeriksa izin..."
            textSize = 14f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, 24)
        }
        mainLayout.addView(statusText)

        // Baris Tombol Izin Sistem
        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        permRow.addView(createButton("1. Aktifkan Izin Aksesibilitas (Tap & XPath)", "#1E88E5") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        permRow.addView(createButton("2. Aktifkan Izin Baca Warna Cepat (RAM Buffer)", "#8E24AA") {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), 1001)
        })

        permRow.addView(createButton("3. Munculkan Panel & Alat Bidik Melayang", "#00897B") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 1002)
            } else {
                showFloatingController()
            }
        })

        mainLayout.addView(permRow)

        // Header Daftar Blok
        val blockHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 32, 0, 16)
        }
        val blockTitle = TextView(this).apply {
            text = "Alur Blok (Gaya Automate):"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = createButton("+ Tambah Blok", "#43A047") {
            val newId = (FlowEngine.blocks.maxOfOrNull { it.id } ?: 0) + 1
            val newBlock = FlowBlock(
                id = newId,
                title = "Blok Baru #$newId",
                type = BlockType.CHECK_COLOR,
                x = lastPickedX,
                y = lastPickedY,
                targetHex = lastPickedHex,
                nextYesId = newId,
                nextNoId = newId
            )
            FlowEngine.blocks.add(newBlock)
            saveBlocks()
            refreshBlocksList()
            showEditBlockDialog(newBlock)
        }
        blockHeader.addView(blockTitle)
        blockHeader.addView(addBtn)
        mainLayout.addView(blockHeader)

        blocksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(blocksContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)

        refreshBlocksList()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val accOk = FastAccessibilityService.instance != null
        val colorOk = ScreenCaptureService.isReady()
        val overlayOk = Settings.canDrawOverlays(this)
        statusText.text = "Aksesibilitas: ${if (accOk) "✅ Aktif" else "❌ Mati"} | " +
                "Baca Warna: ${if (colorOk) "✅ Siap" else "❌ Mati"} | " +
                "Overlay: ${if (overlayOk) "✅ Siap" else "❌ Mati"}"
    }

    private fun refreshBlocksList() {
        blocksContainer.removeAllViews()
        for (block in FlowEngine.blocks) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 24, 30, 24)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 16f
                    setStroke(2, Color.parseColor("#333333"))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 20)
                layoutParams = lp
            }

            val titleTv = TextView(this).apply {
                text = "[ID #${block.id}] ${block.title} (${block.type.name})"
                textSize = 16f
                setTextColor(Color.parseColor("#64B5F6"))
            }

            val detailTv = TextView(this).apply {
                val info = when (block.type) {
                    BlockType.CHECK_COLOR -> "Cek Posisi (${block.x}, ${block.y}) == Warna ${block.targetHex} (±${block.tolerance})"
                    BlockType.TAP_COORDINATE -> "Tap Cepat di (${block.x}, ${block.y})"
                    BlockType.FIND_CLICK_TEXT -> "Cari Teks/ID: '${block.textQuery}' & Klik"
                    BlockType.DELAY_MS -> "Tunggu ${block.delayMs} ms"
                }
                text = "$info\n👉 Jika YES ke: #${block.nextYesId}   |   👉 Jika NO ke: #${block.nextNoId} (0 = Stop)"
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(0, 8, 0, 12)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(createButton("Edit Blok", "#FB8C00") {
                showEditBlockDialog(block)
            })
            val spacer = Space(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) }
            btnRow.addView(spacer)
            btnRow.addView(createButton("Hapus", "#E53935") {
                FlowEngine.blocks.remove(block)
                saveBlocks()
                refreshBlocksList()
            })

            card.addView(titleTv)
            card.addView(detailTv)
            card.addView(btnRow)
            blocksContainer.addView(card)
        }
    }

    private fun showEditBlockDialog(block: FlowBlock) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val nameInput = EditText(this).apply { hint = "Nama Blok"; setText(block.title) }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BlockType.values())
            setSelection(block.type.ordinal)
        }

        val usePickedBtn = Button(this).apply {
            text = "Gunakan Hasil Bidik Terakhir: ($lastPickedX, $lastPickedY) $lastPickedHex"
        }

        val xInput = EditText(this).apply { hint = "Koordinat X"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.x.toString()) }
        val yInput = EditText(this).apply { hint = "Koordinat Y"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.y.toString()) }
        val hexInput = EditText(this).apply { hint = "Warna Hex (#FF0000)"; setText(block.targetHex) }
        val tolInput = EditText(this).apply { hint = "Toleransi Warna (0-100)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.tolerance.toString()) }
        val textQueryInput = EditText(this).apply { hint = "Teks / ID Elemen (XPath)"; setText(block.textQuery) }
        val delayInput = EditText(this).apply { hint = "Jeda Milidetik (ms)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.delayMs.toString()) }
        val yesInput = EditText(this).apply { hint = "Lanjut ke ID jika YES (0=Stop)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.nextYesId.toString()) }
        val noInput = EditText(this).apply { hint = "Lanjut ke ID jika NO (0=Stop)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(block.nextNoId.toString()) }

        usePickedBtn.setOnClickListener {
            xInput.setText(lastPickedX.toString())
            yInput.setText(lastPickedY.toString())
            hexInput.setText(lastPickedHex)
        }

        layout.addView(label("Nama Blok:"))
        layout.addView(nameInput)
        layout.addView(label("Tipe Blok:"))
        layout.addView(typeSpinner)
        layout.addView(usePickedBtn)
        layout.addView(label("Koordinat X & Y:"))
        layout.addView(xInput)
        layout.addView(yInput)
        layout.addView(label("Target Warna Hex & Toleransi:"))
        layout.addView(hexInput)
        layout.addView(tolInput)
        layout.addView(label("Target Teks / ID View (Khusus FIND_CLICK_TEXT):"))
        layout.addView(textQueryInput)
        layout.addView(label("Jeda Waktu ms (Khusus DELAY_MS):"))
        layout.addView(delayInput)
        layout.addView(label("Cabang YES -> Lompat ke Blok ID:"))
        layout.addView(yesInput)
        layout.addView(label("Cabang NO -> Lompat ke Blok ID:"))
        layout.addView(noInput)

        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Edit Blok #${block.id}")
            .setView(scroll)
            .setPositiveButton("Simpan") { _, _ ->
                block.title = nameInput.text.toString()
                block.type = BlockType.values()[typeSpinner.selectedItemPosition]
                block.x = xInput.text.toString().toIntOrNull() ?: 0
                block.y = yInput.text.toString().toIntOrNull() ?: 0
                block.targetHex = hexInput.text.toString().ifEmpty { "#FFFFFF" }
                block.tolerance = tolInput.text.toString().toIntOrNull() ?: 15
                block.textQuery = textQueryInput.text.toString()
                block.delayMs = delayInput.text.toString().toLongOrNull() ?: 50L
                block.nextYesId = yesInput.text.toString().toIntOrNull() ?: 0
                block.nextNoId = noInput.text.toString().toIntOrNull() ?: 0
                saveBlocks()
                refreshBlocksList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // Panel Melayang & Alat Bidik Warna/Koordinat
    private fun showFloatingController() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (floatingView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#DD111111"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#00E676"))
            }
            background = bg
        }

        val infoTv = TextView(this).apply {
            text = "Bidik: ($lastPickedX, $lastPickedY)\nWarna: $lastPickedHex"
            textSize = 11f
            setTextColor(Color.WHITE)
        }

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24).apply {
                setMargins(0, 6, 0, 8)
            }
            setBackgroundColor(Color.RED)
        }

        val playBtn = Button(this).apply {
            text = "▶ START"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!FlowEngine.isRunning) {
                    text = "⏹ STOP"
                    setBackgroundColor(Color.parseColor("#C62828"))
                    FlowEngine.startFlow { log ->
                        runOnUiThread { infoTv.text = log }
                    }
                } else {
                    FlowEngine.stopFlow { log ->
                        runOnUiThread {
                            text = "▶ START"
                            setBackgroundColor(Color.parseColor("#2E7D32"))
                            infoTv.text = log
                        }
                    }
                }
            }
        }

        val toggleCrosshairBtn = Button(this).apply {
            text = "🎯 Bidik Warna"
            textSize = 11f
            setOnClickListener {
                if (crosshairView == null) {
                    showCrosshair(wm, infoTv, colorPreview)
                    text = "❌ Tutup Bidik"
                } else {
                    wm.removeView(crosshairView)
                    crosshairView = null
                    text = "🎯 Bidik Warna"
                }
            }
        }

        val closeBtn = Button(this).apply {
            text = "Tutup Panel"
            textSize = 10f
            setOnClickListener {
                crosshairView?.let { wm.removeView(it); crosshairView = null }
                floatingView?.let { wm.removeView(it); floatingView = null }
            }
        }

        panel.addView(infoTv)
        panel.addView(colorPreview)
        panel.addView(playBtn)
        panel.addView(toggleCrosshairBtn)
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
            x = 20
            y = 200
        }

        // Agar panel bisa digeser di layar
        panel.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        return true
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
    }

    private fun showCrosshair(wm: WindowManager, infoTv: TextView, colorPreview: View) {
        val target = TextView(this).apply {
            text = "➕"
            textSize = 24f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#55000000"))
                setStroke(3, Color.YELLOW)
            }
            background = bg
            setPadding(20, 10, 20, 10)
        }

        val cParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastPickedX
            y = lastPickedY
        }

        target.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = cParams.x; initY = cParams.y
                        touchX = e.rawX; touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
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
            statusText.postDelayed({ updatePermissionStatus() }, 500)
        }
    }

    private fun saveBlocks() {
        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("blocks_json", Gson().toJson(FlowEngine.blocks)).apply()
    }

    private fun loadSavedBlocks() {
        val prefs = getSharedPreferences("fast_auto_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("blocks_json", null) ?: return
        try {
            val type = object : TypeToken<MutableList<FlowBlock>>() {}.type
            val loaded: MutableList<FlowBlock> = Gson().fromJson(json, type)
            if (loaded.isNotEmpty()) {
                FlowEngine.blocks.clear()
                FlowEngine.blocks.addAll(loaded)
            }
        } catch (_: Exception) {}
    }

    private fun label(textStr: String): TextView = TextView(this).apply {
        text = textStr
        setTextColor(Color.GRAY)
        setPadding(0, 12, 0, 4)
    }

    private fun createButton(title: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(colorHex))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }
}
