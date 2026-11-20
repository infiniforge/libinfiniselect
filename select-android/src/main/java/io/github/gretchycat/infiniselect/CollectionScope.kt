package io.github.gretchycat.infiniselect

import java.io.File

/** Shared namespace rules, also enforced by the relay using the verified Android caller package. */
object CollectionScope {
    fun namespace(client: String, callerPackage: String): String {
        require(client.matches(Regex("[a-z][a-z0-9_-]{0,79}"))) { "Invalid collection client" }
        require(callerPackage.matches(Regex("[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+"))) {
            "Invalid caller package"
        }
        return if (
            client.startsWith("infini") && client.length > 6 && callerPackage == "com.$client"
        )
            client.removePrefix("infini")
        else "$callerPackage/$client"
    }

    fun directory(base: File, callerPackage: String, client: String, segments: List<String>): File {
        require(segments.size in 1..4 && segments.all { it.matches(Regex("[a-z0-9_-]{1,80}")) }) {
            "Invalid collection directory"
        }
        val root = File(base.canonicalFile, "infini/" + namespace(client, callerPackage))
        val directory = File(root, segments.joinToString("/"))
        require(
            root.canonicalPath == root.absolutePath &&
                directory.canonicalPath == directory.absolutePath
        ) {
            "Collection paths cannot traverse symbolic links"
        }
        return directory
    }

    fun remove(directory: File, target: String) {
        directory.listFiles().orEmpty().forEach { file ->
            if (
                file.isFile &&
                    file.extension.equals("iflink", true) &&
                    file.length() <= FileShortcut.MAX_BYTES &&
                    runCatching {
                            FileShortcut.decode(
                                    file.inputStream().use {
                                        String(it.readShortcutBytes(), Charsets.UTF_8)
                                    }
                                )
                                .targetUri == target
                        }
                        .getOrDefault(false)
            )
                check(file.delete()) { "Could not remove shortcut" }
        }
    }
}
