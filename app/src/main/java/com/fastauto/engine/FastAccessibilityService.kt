package com.fastauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FastAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FastAccessibilityService? = null
        private val screenshotExecutor = Executors.newSingleThreadExecutor()

        fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 15)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
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
                                if (hwBitmap != null && x >= 0 && y >= 0 && x < hwBitmap.width && y < hwBitmap.height) {
                                    val onePixel = Bitmap.createBitmap(hwBitmap, x, y, 1, 1)
                                    val softPixel = onePixel.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softPixel != null) {
                                        val pixel = softPixel.getPixel(0, 0)
                                        val r = (pixel shr 16) and 0xFF
                                        val g = (pixel shr 8) and 0xFF
                                        val b = pixel and 0xFF
                                        hexResult = String.format("#%02X%02X%02X", r, g, b)
                                        softPixel.recycle()
                                    }
                                    onePixel.recycle()
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
                latch.await(400, TimeUnit.MILLISECONDS)
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
                                    val bmpW = hwBitmap.width
                                    val bmpH = hwBitmap.height
                                    val left = (centerX - radius).coerceAtLeast(0)
                                    val top = (centerY - radius).coerceAtLeast(0)
                                    val right = (centerX + radius).coerceAtMost(bmpW - 1)
                                    val bottom = (centerY + radius).coerceAtMost(bmpH - 1)
                                    val cropW = right - left + 1
                                    val cropH = bottom - top + 1

                                    if (cropW > 0 && cropH > 0) {
                                        val subHw = Bitmap.createBitmap(hwBitmap, left, top, cropW, cropH)
                                        val softBmp = subHw.copy(Bitmap.Config.ARGB_8888, false)
                                        if (softBmp != null) {
                                            outer@ for (dy in 0 until cropH step step) {
                                                for (dx in 0 until cropW step step) {
                                                    val pixel = softBmp.getPixel(dx, dy)
                                                    val hex = String.format(
                                                        "#%02X%02X%02X",
                                                        (pixel shr 16) and 0xFF,
                                                        (pixel shr 8) and 0xFF,
                                                        pixel and 0xFF
                                                    )
                                                    if (ScreenCaptureService.compareHex(hex, targetHex, tolerance)) {
                                                        hitPoint = Pair(left + dx, top + dy)
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
                latch.await(450, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            return hitPoint
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}
