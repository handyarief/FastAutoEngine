package com.fastauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FastAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FastAccessibilityService? = null
        private val screenshotExecutor = Executors.newSingleThreadExecutor()
        @Volatile
        private var lastDismissTime = 0L

        fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 15)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun findAndClickNode(
            targetText: String,
            clickIfFound: Boolean = true
        ): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(targetText)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (!clickIfFound) return true
                    if (clickNodeOrParent(node)) return true
                }
            }
            return searchRecursive(root, targetText.lowercase(), clickIfFound)
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
            var current = node
            var depth = 0
            while (current != null && depth < 6) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
                depth++
            }
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    return tap(rect.centerX(), rect.centerY())
                }
            }
            return false
        }

        private fun searchRecursive(
            node: AccessibilityNodeInfo?,
            query: String,
            clickIfFound: Boolean
        ): Boolean {
            if (node == null) return false
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (text.contains(query) || desc.contains(query) || id.contains(query)) {
                if (!clickIfFound) return true
                if (clickNodeOrParent(node)) return true
            }

            for (i in 0 until node.childCount) {
                if (searchRecursive(node.getChild(i), query, clickIfFound)) return true
            }
            return false
        }

        @SuppressLint("NewApi")
        fun getStealthPixelHex(x: Int, y: Int): String? {
            val service = instance ?: return null
            if (Build.VERSION.SDK_INT < 30) return null

            var hexResult: String? = null
            val latch = CountDownLatch(1)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            try {
                                val cs = screenshotResult.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                                if (hwBitmap != null) {
                                    // Salin ke ARGB_8888 dulu sebelum akses piksel (Hardware Bitmap tidak bisa di-crop langsung di banyak device)
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softBitmap != null && x in 0 until softBitmap.width && y in 0 until softBitmap.height) {
                                        val pixel = softBitmap.getPixel(x, y)
                                        hexResult = String.format("#%06X", pixel and 0xFFFFFF)
                                        softBitmap.recycle()
                                    }
                                    hwBitmap.recycle()
                                }
                            } catch (_: Throwable) {
                            } finally {
                                try { hwBuffer.close() } catch (_: Throwable) {}
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    }
                )
                latch.await(350, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            return hexResult
        }

        @SuppressLint("NewApi")
        fun scanStealthArea(
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetHex: String,
            tolerance: Int
        ): Pair<Int, Int>? {
            val service = instance ?: return null
            if (Build.VERSION.SDK_INT < 30) return null

            var hitPoint: Pair<Int, Int>? = null
            val latch = CountDownLatch(1)
            val targetRgb = ScreenCaptureService.parseHexToInt(targetHex)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            try {
                                val cs = screenshotResult.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                                if (hwBitmap != null) {
                                    val softBmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softBmp != null) {
                                        val left = (centerX - radius).coerceAtLeast(0)
                                        val top = (centerY - radius).coerceAtLeast(0)
                                        val right = (centerX + radius).coerceAtMost(softBmp.width - 1)
                                        val bottom = (centerY + radius).coerceAtMost(softBmp.height - 1)
                                        val cropW = right - left + 1
                                        val cropH = bottom - top + 1

                                        if (cropW > 0 && cropH > 0) {
                                            val pixels = IntArray(cropW * cropH)
                                            softBmp.getPixels(pixels, 0, cropW, left, top, cropW, cropH)

                                            outer@ for (dy in 0 until cropH step step) {
                                                val rowOffset = dy * cropW
                                                for (dx in 0 until cropW step step) {
                                                    val rgb = pixels[rowOffset + dx] and 0xFFFFFF
                                                    if (ScreenCaptureService.compareRgbInt(rgb, targetRgb, tolerance)) {
                                                        hitPoint = Pair(left + dx, top + dy)
                                                        break@outer
                                                    }
                                                }
                                            }
                                        }
                                        softBmp.recycle()
                                    }
                                    hwBitmap.recycle()
                                }
                            } catch (_: Throwable) {
                            } finally {
                                try { hwBuffer.close() } catch (_: Throwable) {}
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    }
                )
                latch.await(380, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            return hitPoint
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastDismissTime < 120L) return

        try {
            val root = rootInActiveWindow ?: return

            // Cek apakah di layar muncul teks peringatan "Risk screen sharing" atau "Potential Telecom Fraud"
            val hasRiskWarning = !root.findAccessibilityNodeInfosByText("Risk screen sharing").isNullOrEmpty() ||
                                 !root.findAccessibilityNodeInfosByText("Potential Telecom Fraud").isNullOrEmpty()

            if (hasRiskWarning) {
                val closeNodes = root.findAccessibilityNodeInfosByText("Close")
                if (!closeNodes.isNullOrEmpty()) {
                    for (node in closeNodes) {
                        if (clickNodeOrParent(node)) {
                            lastDismissTime = now
                            return
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}
