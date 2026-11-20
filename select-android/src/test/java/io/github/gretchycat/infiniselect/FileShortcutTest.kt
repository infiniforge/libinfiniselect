package io.github.gretchycat.infiniselect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileShortcutTest {
    @Test
    fun folderCompatibleJsonKeepsDurableIdentityAndProfile() {
        val link =
            FileShortcut(
                "file:///storage/emulated/0/games/Sonic.gen",
                "Sonic.gen",
                system = "MEGADRIVE",
                createdAtMillis = 123,
            )
        val json = JSONObject(link.encode())
        assertEquals(1, json.getInt("version"))
        assertEquals(link.targetUri, json.getString("targetUri"))
        assertFalse(json.getBoolean("targetIsDirectory"))
        assertEquals(link, FileShortcut.decode(link.encode()))
    }

    @Test
    fun ordinaryFolderLinkWithoutPlayFieldsStillWorks() {
        val link =
            FileShortcut.decode(
                """{"version":1,"targetUri":"sftp://host/games/a.nes","displayNameOverride":"a.nes","targetIsDirectory":false}"""
            )
        assertNull(link.system)
        assertEquals("a.nes", link.displayName)
    }

    @Test
    fun rejectsUnknownVersionsAndTemporaryRelays() {
        for (text in
            listOf(
                """{"version":2,"targetUri":"file:///game"}""",
                """{"version":1,"targetUri":"content://com.infinifolder.selection/temporary"}""",
            )) {
            assertThrows(IllegalArgumentException::class.java) { FileShortcut.decode(text) }
        }
    }

    @Test
    fun networkDirectoryTargetsRoundTripWithoutBeingTreatedAsGameFiles() {
        val json =
            """{"version":1,"targetUri":"smb://saved-connection/roms","displayNameOverride":"Network library","targetIsDirectory":true,"targetMimeType":"vnd.android.document/directory"}"""
        val link = FileShortcut.decode(json)
        assertTrue(link.targetIsDirectory)
        assertEquals("smb://saved-connection/roms", link.targetUri)
        assertEquals(link, FileShortcut.decode(link.encode()))
    }

    @Test
    fun linksAcceptOpaqueAndCustomUrisWithoutASchemeWhitelist() {
        for (uri in
            listOf(
                "https://example.com/collection?q=1#part",
                "mailto:user@example.com",
                "tel:+15551234567",
                "custom-app:resource/42",
                "archive://source/entry",
                "content://provider/document/123",
            )) {
            val link = FileShortcut(uri, "Link", createdAtMillis = 42)
            assertEquals(uri, FileShortcut.decode(link.encode()).targetUri)
        }
    }

    @Test
    fun boundedReaderStopsBeyondLimitInsteadOfReadingWholeSource() {
        val input = java.io.ByteArrayInputStream(ByteArray(FileShortcut.MAX_BYTES * 2))
        assertEquals(FileShortcut.MAX_BYTES + 1, input.readShortcutBytes().size)
        assertTrue(input.available() > 0)
        assertThrows(IllegalArgumentException::class.java) {
            FileShortcut.decode(" ".repeat(FileShortcut.MAX_BYTES + 1))
        }
    }

    @Test
    fun repeatedOpenReusesExistingLinkIncludingUserNamedLinks() {
        val directory = java.nio.file.Files.createTempDirectory("iflink-test").toFile()
        try {
            val link =
                FileShortcut(
                    "file:///storage/emulated/0/Games/a.gen",
                    "a.gen",
                    system = "MEGADRIVE",
                )
            val file = link.writeTo(java.io.File(directory, "recents/megadrive"))
            val renamed = java.io.File(file.parentFile, "My game.iflink")
            assertTrue(file.renameTo(renamed))
            assertEquals(renamed, link.writeTo(renamed.parentFile!!))
            assertEquals(1, renamed.parentFile!!.listFiles()!!.size)
            assertEquals(link, FileShortcut.decode(renamed.readText()))
            val favorite = link.writeTo(java.io.File(directory, "favorites/megadrive"))
            assertTrue(favorite.delete())
            assertTrue(renamed.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
