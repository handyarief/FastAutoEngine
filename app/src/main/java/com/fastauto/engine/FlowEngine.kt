package com.fastauto.engine

import kotlinx.coroutines.*

enum class BlockType {
    CHECK_COLOR,      // Cek warna di (X,Y) -> Cabang YES / NO
    TAP_COORDINATE,   // Tap cepat di (X,Y) -> Lanjut ke YES
    FIND_CLICK_TEXT,  // Cari Teks/ID (XPath) & Klik -> Cabang YES / NO
    DELAY_MS          // Jeda milidetik -> Lanjut ke YES
}

data class FlowBlock(
    val id: Int,
    var title: String,
    var type: BlockType,
    var x: Int = 500,
    var y: Int = 500,
    var targetHex: String = "#FF0000",
    var tolerance: Int = 20,
    var textQuery: String = "",
    var delayMs: Long = 50L,
    var nextYesId: Int = 0, // 0 = Stop, atau ID blok tujuan jika YES
    var nextNoId: Int = 0   // 0 = Stop, atau ID blok tujuan jika NO
)

object FlowEngine {
    var isRunning = false
    private var job: Job? = null
    var statusLog: String = "Siap"

    val blocks = mutableListOf(
        FlowBlock(
            id = 1,
            title = "Cek Warna & Tap Cepat",
            type = BlockType.CHECK_COLOR,
            x = 540, y = 1200,
            targetHex = "#FF0000",
            nextYesId = 2,
            nextNoId = 1 // Ulangi pantau blok 1 terus-menerus tanpa jeda jika belum muncul
        ),
        FlowBlock(
            id = 2,
            title = "Tap Koordinat Target",
            type = BlockType.TAP_COORDINATE,
            x = 540, y = 1200,
            nextYesId = 1,
            nextNoId = 0
        )
    )

    fun startFlow(onUpdate: (String) -> Unit) {
        if (isRunning) return
        isRunning = true
        job = CoroutineScope(Dispatchers.Default).launch {
            var currentId = if (blocks.isNotEmpty()) blocks.first().id else 0
            while (isActive && isRunning && currentId != 0) {
                val block = blocks.find { it.id == currentId }
                if (block == null) {
                    statusLog = "Selesai (Blok #$currentId tidak ada)"
                    break
                }

                statusLog = "Menjalankan Blok #${block.id}: ${block.title}"
                onUpdate(statusLog)

                val result = executeBlock(block)
                currentId = if (result) block.nextYesId else block.nextNoId

                // Jeda mikro 2ms agar HP tidak freeze saat looping ke diri sendiri
                delay(2L)
            }
            isRunning = false
            onUpdate("Berhenti")
        }
    }

    fun stopFlow(onUpdate: (String) -> Unit) {
        isRunning = false
        job?.cancel()
        statusLog = "Dihentikan"
        onUpdate(statusLog)
    }

    private suspend fun executeBlock(b: FlowBlock): Boolean {
        return when (b.type) {
            BlockType.CHECK_COLOR -> {
                ScreenCaptureService.matchColor(b.x, b.y, b.targetHex, b.tolerance)
            }
            BlockType.TAP_COORDINATE -> {
                FastAccessibilityService.tap(b.x, b.y)
            }
            BlockType.FIND_CLICK_TEXT -> {
                FastAccessibilityService.findAndClickNode(b.textQuery, clickIfFound = true)
            }
            BlockType.DELAY_MS -> {
                delay(b.delayMs)
                true
            }
        }
    }
}
