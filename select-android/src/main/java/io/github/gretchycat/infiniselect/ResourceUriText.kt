package io.github.gretchycat.infiniselect

private val resourceScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Validate only the absolute scheme; Android/provider-owned URI text stays opaque. */
internal fun hasAbsoluteResourceScheme(reference: String): Boolean =
    resourceScheme.containsMatchIn(reference)
