package io.github.gretchycat.infiniselect

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.io.File

/** Public relay client. Call on a worker; storage privileges remain with InfiniFolder. */
object FolderStorage {
    const val LIST = "io.github.gretchycat.infiniselect.LIST_DIRECTORY"
    const val COLLECTION = "io.github.gretchycat.infiniselect.SHORTCUT_COLLECTION"

    fun available(context: Context): Boolean =
        context.packageManager.resolveContentProvider(
            FileSelectionContract.INFINIFOLDER_SELECTION_AUTHORITY,
            0,
        ) != null

    internal fun call(context: Context, method: String, extras: Bundle): Bundle =
        context.contentResolver.call(
            Uri.parse("content://" + FileSelectionContract.INFINIFOLDER_SELECTION_AUTHORITY),
            method,
            null,
            extras,
        )
            ?: throw UnsupportedOperationException(
                "Update InfiniFolder to use relayed browsing and collections"
            )

    data class Entry(
        val uri: Uri,
        val name: String,
        val label: String,
        val mime: String,
        val directory: Boolean,
        val size: Long?,
        val modified: Long,
    )

    data class Listing(val entries: List<Entry>, val loading: Boolean, val notice: String?)

    fun list(context: Context, directory: Uri?): Listing {
        val entries = linkedMapOf<String, Entry>()
        var offset = 0
        var loading = false
        var notice: String? = null
        do {
            val reply =
                call(
                    context,
                    LIST,
                    Bundle().apply {
                        putString("directory", directory?.toString())
                        putInt("offset", offset)
                    },
                )
            require(reply.getInt("version") == 1) { "Unsupported directory relay version" }
            for (row in DirectoryListingEntry.decodePage(reply.getString("entries") ?: "[]")) {
                entries[row.uri] =
                    Entry(
                        Uri.parse(row.uri),
                        row.name,
                        row.label,
                        row.mime,
                        row.directory,
                        row.size,
                        row.modified,
                    )
            }
            loading = loading || reply.getBoolean("loading")
            notice = reply.getString("notice") ?: notice
            val next = reply.getInt("next", -1)
            require(next < 0 || next > offset) { "Directory relay did not advance" }
            offset = next
            require(entries.size <= 20000) { "Directory is too large to display" }
        } while (offset >= 0)
        return Listing(entries.values.toList(), loading, notice)
    }
}

/**
 * Per-client link collections; uses Folder IPC, with app-private storage only when Folder is
 * absent.
 */
class ShortcutCollections(private val context: Context, private val clientId: String) {
    init {
        CollectionScope.namespace(clientId, context.packageName)
    }

    val relayed: Boolean
        get() = FolderStorage.available(context)

    private fun request(
        operation: String,
        kind: String,
        system: String?,
        shortcut: FileShortcut? = null,
        touch: Boolean = false,
    ): Bundle {
        return FolderStorage.call(
            context,
            FolderStorage.COLLECTION,
            Bundle().apply {
                putString("client", clientId)
                putString("operation", operation)
                putStringArrayList("segments", ArrayList(listOfNotNull(kind, system)))
                shortcut?.let { putString("shortcut", it.encode()) }
                putBoolean("touch", touch)
            },
        )
    }

    private fun local(kind: String, system: String?): File =
        CollectionScope.directory(
                context.filesDir,
                context.packageName,
                clientId,
                listOfNotNull(kind, system),
            )
            .apply { check(isDirectory || mkdirs()) { "Cannot create private collection" } }

    fun directory(kind: String, system: String? = null): Uri =
        if (relayed)
            Uri.parse(
                request("directory", kind, system).getString("uri")
                    ?: error("Missing collection URI")
            )
        else Uri.fromFile(local(kind, system))

    fun put(kind: String, system: String, shortcut: FileShortcut, touch: Boolean = false): Uri =
        if (relayed)
            Uri.parse(
                request("put", kind, system, shortcut, touch).getString("uri")
                    ?: error("Missing link URI")
            )
        else
            Uri.fromFile(
                shortcut.writeTo(local(kind, system)).apply {
                    if (touch) setLastModified(System.currentTimeMillis())
                }
            )

    fun remove(kind: String, system: String, shortcut: FileShortcut) {
        if (relayed) request("remove", kind, system, shortcut)
        else CollectionScope.remove(local(kind, system), shortcut.targetUri)
    }
}
