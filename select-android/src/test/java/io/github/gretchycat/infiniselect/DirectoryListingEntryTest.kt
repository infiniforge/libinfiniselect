package io.github.gretchycat.infiniselect

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DirectoryListingEntryTest {
    @Test
    fun jsonKeepsDirectoryAndFileMetadataWithoutImplyingReadGrant() {
        val rows =
            listOf(
                DirectoryListingEntry(
                    "sftp://host/games",
                    "games",
                    "Games",
                    "vnd.android.document/directory",
                    true,
                    null,
                    100,
                ),
                DirectoryListingEntry(
                    "file:///storage/emulated/0/game.gen",
                    "game.gen",
                    "My game",
                    "application/octet-stream",
                    false,
                    524288,
                    200,
                ),
            )
        val json = JSONArray().apply { rows.forEach { put(it.toJson()) } }.toString()
        assertEquals(rows, DirectoryListingEntry.decodePage(json))
        assertFalse(json.contains("contentUri"))
    }

    @Test
    fun rejectsUnboundedPagesAndRelativeLocations() {
        val row =
            DirectoryListingEntry(
                "file:///game",
                "game",
                "game",
                "application/octet-stream",
                false,
                1,
                0,
            )
        val json = JSONArray().apply { repeat(257) { put(row.toJson()) } }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryListingEntry.decodePage(json)
        }
        val relative = JSONArray().put(row.copy(uri = "../game").toJson()).toString()
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryListingEntry.decodePage(relative)
        }
    }
}
