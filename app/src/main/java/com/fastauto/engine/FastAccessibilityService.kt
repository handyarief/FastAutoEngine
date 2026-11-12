// Ganti fungsi getStealthPixelHex dan scanStealthArea di FastAccessibilityService.kt dengan ini:

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
                                if (hwBitmap != null && x in 0 until hwBitmap.width && y in 0 until hwBitmap.height) {
                                    // Crop 1x1 langsung dan salin ke ARGB_8888 dalam satu langkah cepat
                                    val onePixel = Bitmap.createBitmap(hwBitmap, x, y, 1, 1)
                                    val softPixel = onePixel.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softPixel != null) {
                                        val pixel = softPixel.getPixel(0, 0)
                                        hexResult = String.format("#%06X", pixel and 0xFFFFFF)
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
                                            // Salin seluruh area crop ke IntArray sekaligus (10x lebih cepat dari getPixel berulang)
                                            val pixels = IntArray(cropW * cropH)
                                            softBmp.getPixels(pixels, 0, cropW, 0, 0, cropW, cropH)

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
                latch.await(380, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            return hitPoint
        }
