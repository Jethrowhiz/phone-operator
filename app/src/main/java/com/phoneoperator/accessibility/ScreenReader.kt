package com.phoneoperator.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScreenElement(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: List<Int>, // [left, top, right, bottom]
    val index: Int
)

@Serializable
data class ScreenSnapshot(
    val packageName: String?,
    val elements: List<ScreenElement>
)

/**
 * Walks the accessibility node tree once and produces a flat, compact list
 * of interactive/text elements — deliberately NOT the full raw tree, to keep
 * the payload small when it's sent to the AI as context.
 */
object ScreenReader {

    private const val MAX_ELEMENTS = 200 // safety cap for pathological trees

    fun capture(service: PhoneOperatorAccessibilityService): ScreenSnapshot {
        val root = service.currentRoot()
        val elements = mutableListOf<ScreenElement>()
        if (root != null) {
            walk(root, elements)
        }
        return ScreenSnapshot(packageName = root?.packageName?.toString(), elements = elements)
    }

    fun toJson(snapshot: ScreenSnapshot): String = Json.encodeToString(snapshot)

    /** Finds elements whose text or content-description contains [query] (case-insensitive). */
    fun findByText(snapshot: ScreenSnapshot, query: String): List<ScreenElement> {
        val q = query.lowercase()
        return snapshot.elements.filter {
            it.text?.lowercase()?.contains(q) == true ||
                it.contentDescription?.lowercase()?.contains(q) == true
        }
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>) {
        if (out.size >= MAX_ELEMENTS) return

        val hasSignal = !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            node.isClickable || node.isEditable

        if (hasSignal) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            out.add(
                ScreenElement(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    index = out.size
                )
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                walk(child, out)
                child.recycle()
            }
        }
    }
}
