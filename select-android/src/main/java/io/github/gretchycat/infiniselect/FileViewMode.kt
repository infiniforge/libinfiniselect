package io.github.gretchycat.infiniselect

/** Optional initial view preference; users can still switch modes in the browser. */
enum class FileViewMode {
    LIST,
    ICON;

    companion object {
        fun fromWire(value: String?): FileViewMode? =
            entries.firstOrNull { it.name.equals(value, true) }

        fun initialIconMode(preferred: FileViewMode?, savedIconMode: Boolean): Boolean =
            preferred?.let { it == ICON } ?: savedIconMode
    }
}
