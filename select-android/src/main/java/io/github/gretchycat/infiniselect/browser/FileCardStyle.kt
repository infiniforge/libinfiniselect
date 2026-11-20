package io.github.gretchycat.infiniselect.browser

import android.content.Context
import android.os.Bundle

/** Presentation metadata only: never opens a source or interprets a backend URI. */
data class FileCardEntry(
    val directory: Boolean,
    val shortcut: Boolean = false,
    val symbolicLink: Boolean = false,
    val brokenLink: Boolean = false,
    val deleted: Boolean = false,
    val application: Boolean = false,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val hidden: Boolean = false,
)

/** Explicit appearance keeps the same card independent of Folder's theme manager. */
data class FileCardStyle(
    val textPrimary: Int,
    val textSecondary: Int,
    val selectionText: Int,
    val selectionBackground: Int,
    val accent: Int,
    val fontMonospace: Boolean,
    val isDark: Boolean,
    val background: Int,
) {
    fun toBundle() =
        Bundle().apply {
            putInt("textPrimary", textPrimary)
            putInt("textSecondary", textSecondary)
            putInt("selectionText", selectionText)
            putInt("selectionBackground", selectionBackground)
            putInt("accent", accent)
            putBoolean("fontMonospace", fontMonospace)
            putBoolean("isDark", isDark)
            putInt("background", background)
        }

    companion object {
        fun from(context: Context): FileCardStyle {
            val attrs =
                context.obtainStyledAttributes(
                    intArrayOf(
                        android.R.attr.textColorPrimary,
                        android.R.attr.textColorSecondary,
                        android.R.attr.colorAccent,
                        android.R.attr.colorBackground,
                    )
                )
            try {
                val text = attrs.getColor(0, 0xffeeeeee.toInt())
                val secondary = attrs.getColor(1, 0xffaaaaaa.toInt())
                val accent = attrs.getColor(2, 0xff1976d2.toInt())
                val background = attrs.getColor(3, 0xff202020.toInt())
                val dark = android.graphics.Color.luminance(background) < .5
                return FileCardStyle(text, secondary, text, accent, accent, false, dark, background)
            } finally {
                attrs.recycle()
            }
        }

        fun fromBundle(bundle: Bundle) =
            FileCardStyle(
                bundle.getInt("textPrimary"),
                bundle.getInt("textSecondary"),
                bundle.getInt("selectionText"),
                bundle.getInt("selectionBackground"),
                bundle.getInt("accent"),
                bundle.getBoolean("fontMonospace"),
                bundle.getBoolean("isDark"),
                bundle.getInt("background"),
            )
    }
}
