
package com.fastauto.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BlockType {
    CHECK_COLOR,
    TAP_COORDINATE,
    FIND_CLICK_TEXT,
    DELAY_MS
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
    var nextYesId: Int = 0,
    var nextNoId: Int = 0
)

object FlowEngine {

    // ==================================================
    // ENGINE STATE
    // ==================================================

    @Volatile
    var isRunning: Boolean = false

    @Volatile
    var statusLog: String = "Siap"

    private val stateLock = Any()

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private var job: Job? = null

    /*
     * Mencegah coroutine lama mengubah status
     * ketika flow baru sudah dijalankan.
     */
    private var runGeneration = 0L

    /*
     * Jeda pendek untuk mencegah loop terlalu rapat
     * dan memberi kesempatan proses dibatalkan.
     */
    private const val LOOP_INTERVAL_MS = 2L

    // ==================================================
    // FLOW BLOCKS
    // ==================================================

    val blocks = mutableListOf(

        FlowBlock(
            id = 1,
            title = "Cek Warna & Tap Cepat",
            type = BlockType.CHECK_COLOR,
            x = 540,
            y = 1200,
            targetHex = "#FF0000",
            nextYesId = 2,
            nextNoId = 1
        ),

        FlowBlock(
            id = 2,
            title = "Tap Koordinat Target",
            type = BlockType.TAP_COORDINATE,
            x = 540,
            y = 1200,
            nextYesId = 1,
            nextNoId = 0
        )
    )

    // ==================================================
    // SAFE STATUS UPDATE
    // ==================================================

    private fun notifyUpdate(
        callback: (String) -> Unit,
        message: String
    ) {
        try {
            callback(message)
        } catch (_: Exception) {
            /*
             * Kesalahan callback tidak boleh
             * menghentikan mesin flow.
             */
        }
    }

    // ==================================================
    // START FLOW
    // ==================================================

    fun startFlow(onUpdate: (String) -> Unit) {

        /*
         * Ambil salinan blok saat flow dimulai.
         * Perubahan daftar blok setelah mulai tidak
         * mengubah urutan flow yang sedang berjalan.
         */
        val blockSnapshot = synchronized(blocks) {
            blocks.map { it.copy() }
        }

        if (blockSnapshot.isEmpty()) {

            statusLog = "Tidak ada blok untuk dijalankan"

            notifyUpdate(
                onUpdate,
                statusLog
            )

            return
        }

        synchronized(stateLock) {

            if (isRunning) return

            isRunning = true

            runGeneration++

            val currentGeneration = runGeneration

            val newJob = engineScope.launch(
                start = CoroutineStart.LAZY
            ) {

                var finalStatus = "Selesai"

                try {

                    // ----------------------------------
                    // VALIDATE BLOCK IDS
                    // ----------------------------------

                    val blockMap = blockSnapshot.associateBy {
                        it.id
                    }

                    if (blockMap.size != blockSnapshot.size) {

                        finalStatus =
                            "Gagal: ditemukan ID blok duplikat"

                        return@launch
                    }

                    // ----------------------------------
                    // START FROM FIRST BLOCK
                    // ----------------------------------

                    var currentId = blockSnapshot.first().id

                    while (
                        currentId != 0 &&
                        currentCoroutineContext().isActive
                    ) {

                        val block = blockMap[currentId]

                        if (block == null) {

                            finalStatus =
                                "Selesai: Blok #$currentId tidak ditemukan"

                            break
                        }

                        // ------------------------------
                        // UPDATE STATUS
                        // ------------------------------

                        val runningStatus =
                            "Menjalankan Blok #${block.id}: ${block.title}"

                        statusLog = runningStatus

                        notifyUpdate(
                            onUpdate,
                            runningStatus
                        )

                        // ------------------------------
                        // EXECUTE BLOCK
                        // ------------------------------

                        val result = executeBlock(block)

                        // ------------------------------
                        // YES / NO ROUTING
                        // ------------------------------

                        currentId = if (result) {
                            block.nextYesId
                        } else {
                            block.nextNoId
                        }

                        /*
                         * Jeda singkat setelah eksekusi.
                         * Tetap responsif terhadap cancel,
                         * tetapi menghindari tight loop.
                         */
                        if (currentId != 0) {
                            delay(LOOP_INTERVAL_MS)
                        }
                    }

                } catch (cancelled: CancellationException) {

                    finalStatus = "Dihentikan"

                    throw cancelled

                } catch (error: Exception) {

                    finalStatus =
                        "Gagal: ${error.message ?: "Kesalahan mesin flow"}"

                } finally {

                    val shouldNotify = synchronized(stateLock) {

                        /*
                         * Hanya proses dengan generasi
                         * terbaru yang boleh mengubah status.
                         */
                        if (runGeneration == currentGeneration) {

                            isRunning = false

                            job = null

                            statusLog = finalStatus

                            true

                        } else {
                            false
                        }
                    }

                    if (shouldNotify) {
                        notifyUpdate(
                            onUpdate,
                            finalStatus
                        )
                    }
                }
            }

            job = newJob

            newJob.start()
        }
    }

    // ==================================================
    // STOP FLOW
    // ==================================================

    fun stopFlow(onUpdate: (String) -> Unit) {

        val runningJob: Job?

        synchronized(stateLock) {

            /*
             * Naikkan generasi agar coroutine lama
             * tidak bisa menimpa status flow berikutnya.
             */
            runGeneration++

            runningJob = job

            job = null

            isRunning = false

            statusLog = "Dihentikan"
        }

        runningJob?.cancel()

        notifyUpdate(
            onUpdate,
            "Dihentikan"
        )
    }

    // ==================================================
    // HEX VALIDATION
    // ==================================================

    private fun isValidHex(hex: String): Boolean {

        return HEX_PATTERN.matches(
            hex.trim()
        )
    }

    private val HEX_PATTERN = Regex(
        "^#?(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$"
    )

    // ==================================================
    // BLOCK EXECUTION
    // ==================================================

    private suspend fun executeBlock(
        block: FlowBlock
    ): Boolean {

        return when (block.type) {

            // ------------------------------------------
            // CHECK COLOR
            // ------------------------------------------

            BlockType.CHECK_COLOR -> {

                if (block.x < 0 || block.y < 0) {
                    return false
                }

                if (block.tolerance !in 0..255) {
                    return false
                }

                if (!isValidHex(block.targetHex)) {
                    return false
                }

                ScreenCaptureService.matchColor(
                    block.x,
                    block.y,
                    block.targetHex,
                    block.tolerance
                )
            }

            // ------------------------------------------
            // TAP COORDINATE
            // ------------------------------------------

            BlockType.TAP_COORDINATE -> {

                if (block.x < 0 || block.y < 0) {
                    return false
                }

                FastAccessibilityService.tap(
                    block.x,
                    block.y
                )
            }

            // ------------------------------------------
            // FIND AND CLICK TEXT
            // ------------------------------------------

            BlockType.FIND_CLICK_TEXT -> {

                val query = block.textQuery.trim()

                if (query.isEmpty()) {
                    return false
                }

                FastAccessibilityService.findAndClickNode(
                    query,
                    clickIfFound = true
                )
            }

            // ------------------------------------------
            // DELAY
            // ------------------------------------------

            BlockType.DELAY_MS -> {

                delay(
                    block.delayMs.coerceAtLeast(0L)
                )

                true
            }
        }
    }
}
