package com.fastauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FastAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FastAccessibilityService? = null
        private val screenshotExecutor = Executors.newSingleThreadExecutor()
        @Volatile
        private var lastDismissTime = 0L
        private val mainHandler = Handler(Looper.getMainLooper())

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

            var rgbResult: Int? = null
            val latch = CountDownLatch(1)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            try {
                                val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, srgb)
                                if (hwBitmap != null) {
                                    if (x in 0 until hwBitmap.width && y in 0 until hwBitmap.height) {
                                        val pixelBmp = Bitmap.createBitmap(hwBitmap, x, y, 1, 1)
                                        val softBmp = pixelBmp.copy(Bitmap.Config.ARGB_8888, false)
                                        if (softBmp != null) {
                                            rgbResult = softBmp.getPixel(0, 0) and 0xFFFFFF
                                            softBmp.recycle()
                                        }
                                        pixelBmp.recycle()
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
                latch.await(300, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            val rgb = rgbResult ?: return null
            return String.format("#%06X", rgb)
        }

        @SuppressLint("NewApi")
        fun scanStealthAreaRgb(
            centerX: Int,
            centerY: Int,
            radius: Int,
            step: Int,
            targetRgb: Int,
            tolerance: Int
        ): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 30) return false

            var matched = false
            val latch = CountDownLatch(1)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            try {
                                val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, srgb)
                                if (hwBitmap != null) {
                                    val left = (centerX - radius).coerceAtLeast(0)
                                    val top = (centerY - radius).coerceAtLeast(0)
                                    val right = (centerX + radius).coerceAtMost(hwBitmap.width - 1)
                                    val bottom = (centerY + radius).coerceAtMost(hwBitmap.height - 1)
                                    val cropW = right - left + 1
                                    val cropH = bottom - top + 1

                                    if (cropW > 0 && cropH > 0) {
                                        val subHw = Bitmap.createBitmap(hwBitmap, left, top, cropW, cropH)
                                        val softBmp = subHw.copy(Bitmap.Config.ARGB_8888, false)
                                        if (softBmp != null) {
                                            val pixels = IntArray(cropW * cropH)
                                            softBmp.getPixels(pixels, 0, cropW, 0, 0, cropW, cropH)

                                            outer@ for (dy in 0 until cropH step step) {
                                                val rowOffset = dy * cropW
                                                for (dx in 0 until cropW step step) {
                                                    val rgb = pixels[rowOffset + dx] and 0xFFFFFF
                                                    if (ScreenCaptureService.compareRgbInt(rgb, targetRgb, tolerance)) {
                                                        matched = true
                                                        break@outer
                                                    }
                                                }
                                            }
                                            softBmp.recycle()
                                        }
                                        subHw.recycle()
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
                latch.await(320, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            return matched
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
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastDismissTime < 40L) return

        if (tryDismissSecurityPopup()) {
            lastDismissTime = System.currentTimeMillis()
            // Sapu bersih dialog ke-2 apabila muncul beruntun (misal: Risk screen sharing -> Potential Telecom Fraud)
            mainHandler.postDelayed({ tryDismissSecurityPopup() }, 120L)
            mainHandler.postDelayed({ tryDismissSecurityPopup() }, 260L)
        }
    }

    private fun tryDismissSecurityPopup(): Boolean {
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && checkAndClickRiskDialog(activeRoot)) {
                return true
            }

            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                for (win in windowList) {
                    val root = win.root ?: continue
                    if (root == activeRoot) continue
                    if (checkAndClickRiskDialog(root)) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun checkAndClickRiskDialog(root: AccessibilityNodeInfo): Boolean {
        val hasRiskWarning =
            !root.findAccessibilityNodeInfosByText("Risk screen sharing").isNullOrEmpty() ||
            !root.findAccessibilityNodeInfosByText("Potential Telecom Fraud").isNullOrEmpty()

        if (!hasRiskWarning) return false

        val buttonLabels = arrayOf("Close", "Tutup", "OK")
        for (label in buttonLabels) {
            val closeNodes = root.findAccessibilityNodeInfosByText(label)
            if (!closeNodes.isNullOrEmpty()) {
                for (node in closeNodes) {
                    if (clickNodeOrParent(node)) {
                        return true
                    }
                }
            }
        }

        val buttonIds = arrayOf(
            "android:id/button1",
            "android:id/button2",
            "android:id/button3"
        )
        for (btnId in buttonIds) {
            val idNodes = root.findAccessibilityNodeInfosByViewId(btnId)
            if (!idNodes.isNullOrEmpty()) {
                for (node in idNodes) {
                    if (clickNodeOrParent(node)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
    }
}
