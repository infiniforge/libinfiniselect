package io.github.gretchycat.infiniselect

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CollectionScopeTest {
    @Test
    fun verifiedAppGetsRequestedPlayDirectoryOtherCallersCannotClaimIt() {
        val base = Files.createTempDirectory("collection-scope").toFile()
        try {
            assertEquals(
                File(base.canonicalFile, "infini/play/recents/nes"),
                CollectionScope.directory(
                    base,
                    "com.infiniplay",
                    "infiniplay",
                    listOf("recents", "nes"),
                ),
            )
            val other =
                CollectionScope.directory(
                    base,
                    "org.example.viewer",
                    "infiniplay",
                    listOf("recents", "nes"),
                )
            assertTrue(other.path.contains("infini/org.example.viewer/infiniplay/"))
            assertFalse(other.path.startsWith(File(base, "infini/play/").path))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndSymbolicLinkEscapes() {
        val base = Files.createTempDirectory("collection-scope").toFile()
        val outside = Files.createTempDirectory("collection-outside").toFile()
        try {
            for (segment in listOf("..", ".", "/games", "recents/nes", "%2e%2e", "")) {
                assertThrows(IllegalArgumentException::class.java) {
                    CollectionScope.directory(base, "com.infiniplay", "infiniplay", listOf(segment))
                }
            }
            val root = File(base, "infini/play")
            root.mkdirs()
            Files.createSymbolicLink(File(root, "recents").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                CollectionScope.directory(
                    base,
                    "com.infiniplay",
                    "infiniplay",
                    listOf("recents", "nes"),
                )
            }
        } finally {
            base.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun removalTouchesOnlyMatchingLinksAndKeepsSourceAndOtherLinks() {
        val base = Files.createTempDirectory("collection-scope").toFile()
        try {
            val source = File(base, "original.gen").apply { writeText("original") }
            val dir = File(base, "links")
            val favorite = FileShortcut(source.toURI().toString(), "original.gen").writeTo(dir)
            val other = FileShortcut("file:///other.gen", "other.gen").writeTo(dir)
            CollectionScope.remove(dir, source.toURI().toString())
            assertFalse(favorite.exists())
            assertTrue(other.exists())
            assertEquals("original", source.readText())
        } finally {
            base.deleteRecursively()
        }
    }
}
