package io.github.gretchycat.infiniselect

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Interoperable version-1 InfiniFolder .iflink document. Stores identities, never access relays.
 */
data class FileShortcut(
    val targetUri: String,
    val displayName: String,
    val mimeType: String = "application/octet-stream",
    val system: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(
            targetUri.isNotBlank() &&
                java.net.URI(targetUri).isAbsolute &&
                !isTemporarySelectionReference(targetUri)
        ) {
            "Link needs a durable target"
        }
    }

    fun encode(): String =
        JSONObject()
            .apply {
                put("version", 1)
                put("targetUri", targetUri)
                put("displayNameOverride", displayName)
                put("targetMimeType", mimeType)
                put("targetIsDirectory", false)
                put("createdAtMillis", createdAtMillis)
                system?.let { put("infiniPlaySystem", it) }
            }
            .toString(2)

    /** Create one link per authoritative identity, including links created by InfiniFolder. */
    fun writeTo(directory: java.io.File): java.io.File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create link directory" }
        directory
            .listFiles()
            .orEmpty()
            .firstOrNull { file ->
                file.isFile &&
                    file.extension.equals("iflink", true) &&
                    file.length() <= MAX_BYTES &&
                    runCatching {
                            decode(
                                    file.inputStream().use {
                                        String(it.readShortcutBytes(), Charsets.UTF_8)
                                    }
                                )
                                .targetUri == targetUri
                        }
                        .getOrDefault(false)
            }
            ?.let {
                return it
            }
        val key =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(targetUri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val target = java.io.File(directory, "$key.iflink")
        require(!target.exists()) { "A different link occupies this name" }
        val temp = java.io.File.createTempFile(".link-", ".tmp", directory)
        try {
            val text = encode()
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Link is too large" }
            temp.writeText(text)
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temp.delete()
        }
        return target
    }

    companion object {
        const val MIME = "application/vnd.infinifolder.shortcut+json"
        const val MAX_BYTES = 128 * 1024

        fun decode(text: String): FileShortcut {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Link is too large" }
            val json = JSONObject(text)
            require(json.optInt("version", 1) == 1 && !json.optBoolean("targetIsDirectory")) {
                "Unsupported file link"
            }
            val target = json.getString("targetUri")
            require(target.isNotBlank() && !isTemporarySelectionReference(target)) {
                "Link needs a durable target"
            }
            return FileShortcut(
                target,
                json.optString("displayNameOverride").ifBlank {
                    target.substringAfterLast('/').ifBlank { "File" }
                },
                json.optString("targetMimeType", "application/octet-stream"),
                json.optString("infiniPlaySystem").takeIf { it.isNotBlank() },
                json.optLong("createdAtMillis", 0),
            )
        }

        fun read(context: Context, uri: Uri): FileShortcut =
            StorageAccess.openRead(context, uri).use {
                val bytes = it.readShortcutBytes()
                require(bytes.size <= MAX_BYTES) { "Link is too large" }
                decode(String(bytes, Charsets.UTF_8))
            }
    }
}

internal fun java.io.InputStream.readShortcutBytes(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() <= FileShortcut.MAX_BYTES) {
        val n = read(buffer, 0, minOf(buffer.size, FileShortcut.MAX_BYTES + 1 - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
