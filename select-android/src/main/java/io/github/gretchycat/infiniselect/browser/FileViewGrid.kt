package io.github.gretchycat.infiniselect.browser

import android.content.Context
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow

/** Shared normal, floating and embedded browser layout. */
object FileViewGrid {
    fun columns(availableWidthDp: Int, iconSizeDp: Int): Int =
        Math.round(availableWidthDp / (iconSizeDp * 1.35f).coerceAtLeast(36f)).coerceIn(1, 14)

    fun <T> build(
        context: Context,
        entries: List<T>,
        columns: Int,
        onEmptyAreaLongPress: (() -> Boolean)? = null,
        card: (T) -> View,
    ): TableLayout {
        fun View.emptyAreaAction() {
            isLongClickable = onEmptyAreaLongPress != null
            if (onEmptyAreaLongPress != null) setOnLongClickListener { onEmptyAreaLongPress() }
        }
        return TableLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            isStretchAllColumns = true
            emptyAreaAction()
            entries.chunked(columns).forEach { group ->
                val row =
                    TableRow(context).apply {
                        clipChildren = false
                        clipToPadding = false
                        emptyAreaAction()
                    }
                group.forEach { entry ->
                    row.addView(
                        card(entry),
                        TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                repeat(columns - group.size) {
                    row.addView(
                        View(context).apply { emptyAreaAction() },
                        TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                addView(row)
            }
        }
    }
}
