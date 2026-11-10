package com.fastauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FastAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FastAccessibilityService? = null

        fun tap(x: Int, y: Int, durationMs: Long = 15L): Boolean {
            val service = instance ?: return false
            if (x < 0 || y < 0) return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        // Cari elemen berdasarkan Teks atau ID (Mirip XPath cepat) lalu klik
        fun findAndClickNode(query: String, clickIfFound: Boolean = true): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val targetNode = searchNodeRecursive(root, query.trim())
            if (targetNode != null) {
                if (clickIfFound) {
                    if (! performNodeClick(targetNode)) {
                        val rect = Rect()
                        targetNode.getBoundsInScreen(rect)
                        tap(rect.centerX(), rect.centerY())
                    }
                }
                return true
            }
            return false
        }

        private fun performNodeClick(node: AccessibilityNodeInfo?): Boolean {
            var current = node
            while (current != null) {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
            return false
        }

        private fun searchNodeRecursive(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
            if (node == null) return null
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (text.contains(query, ignoreCase = true) ||
                desc.contains(query, ignoreCase = true) ||
                viewId.contains(query, ignoreCase = true)) {
                return node
            }

            for (i in 0 until node.childCount) {
                val found = searchNodeRecursive(node.getChild(i), query)
                if (found != null) return found
            }
            return null
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
        instance = null
    }
}
