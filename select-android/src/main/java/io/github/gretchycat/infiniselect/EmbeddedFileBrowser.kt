package io.github.gretchycat.infiniselect

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.gretchycat.infiniselect.browser.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Embeddable source browser. Folder storage is accessed only through its grant-checked relay; file
 * cards, names, grid and viewport are the normal InfiniFolder renderer.
 */
class EmbeddedFileBrowser
@JvmOverloads
constructor(context: Context, preferredViewMode: FileViewMode? = null) : LinearLayout(context) {
    var sortByModified: Boolean = false
    var onSelected: (SelectionItem) -> Unit = {}
    var fileActions: (SelectionItem) -> List<SelectionAction> = { emptyList() }
    var onFileAction: (String, SelectionItem) -> Unit = { _, _ -> }
    var onFileMenu: (SelectionItem) -> Unit = {}
    var onRequestTree: (Uri?) -> Unit = {}
    var canBrowseLocal: () -> Boolean = {
        File(Environment.getExternalStorageDirectory().path).canRead()
    }
    var onRequestLocalAccess: () -> Unit = {}
    var onRequestOpen: () -> Unit = {}
    var onError: (String) -> Unit = {}
    private val prefs = context.getSharedPreferences("infiniselect.embedded", 0)
    private val main = Handler(Looper.getMainLooper())
    private var executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var current: Uri? = null
    private var trail = mutableListOf<Pair<Uri?, String>>()
    private var entries = emptyList<Entry>()
    private val navigation = LinearLayout(context)
    private val path = TextView(context)
    private val toggle = Button(context)
    private val status = TextView(context)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private var appearance = FileCardStyle.from(context)
    private var surface: VirtualizedEntrySurface<Entry>? = null
    @Volatile private var renderGeneration = 0
    private var artworkExecutor = Executors.newFixedThreadPool(2)
    private val artworkCache =
        object : android.util.LruCache<String, FolderStorage.Artwork>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: FolderStorage.Artwork) =
                value.bitmap.allocationByteCount
        }
    private var observed: Uri? = null
    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
    var breadcrumbsEnabled: Boolean = prefs.getBoolean("breadcrumbs", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("breadcrumbs", value).apply()
            navigation.visibility = if (value) VISIBLE else GONE
        }

    var iconMode: Boolean =
        FileViewMode.initialIconMode(preferredViewMode, prefs.getBoolean("icons", false))
        set(value) {
            field = value
            prefs.edit().putBoolean("icons", value).apply()
            render()
        }

    data class Entry(
        val uri: Uri,
        val name: String,
        val directory: Boolean,
        val mime: String,
        val size: Long?,
        val label: String = name,
        val modified: Long = 0,
        val relayed: Boolean = false,
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(appearance.background)
        navigation.gravity = Gravity.CENTER_VERTICAL
        navigation.addView(button("‹") { goBack() })
        path.setTextColor(0xffd8e6ee.toInt())
        path.setPadding(8, 8, 8, 8)
        navigation.addView(path, LayoutParams(0, -2, 1f))
        navigation.addView(toggle)
        toggle.isAllCaps = false
        toggle.setOnClickListener { iconMode = !iconMode }
        addView(navigation)
        addView(status)
        scroll.addView(content)
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            surface?.updateViewport(y, scroll.height)
        }
        addView(scroll, LayoutParams(-1, 0, 1f))
        navigation.visibility = if (breadcrumbsEnabled) VISIBLE else GONE
    }

    private fun button(label: String, action: () -> Unit) =
        Button(context).apply {
            text = label
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
        }

    /**
     * null is Virtual root; a directory is a navigation boundary (Recents/Favorites stay within
     * it).
     */
    fun open(directory: Uri? = null, label: String = "Virtual root") {
        current = directory
        scroll.scrollTo(0, 0)
        trail = mutableListOf(directory to label)
        refresh()
    }

    fun location(): Uri? = current

    fun goBack(): Boolean {
        if (trail.size <= 1) return false
        trail.removeAt(trail.lastIndex)
        current = trail.last().first
        refresh()
        return true
    }

    fun refresh() {
        val token = ++generation
        val directory = current
        path.text = trail.joinToString(" › ") { it.second }
        status.text = "Loading…"
        status.setTextColor(0xffd8e6ee.toInt())
        observed?.let { context.contentResolver.unregisterContentObserver(observer) }
        observed = null
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val style =
                if (FolderStorage.available(context)) FolderStorage.browserStyle(context)
                else appearance
            val result = runCatching { if (directory == null) roots() else list(directory) }
            main.post {
                if (token != generation) return@post
                appearance = style
                result.fold(
                    {
                        entries = it
                        status.text = if (it.isEmpty()) "This folder is empty" else ""
                        render()
                        if (directory?.scheme == "content")
                            runCatching {
                                val children =
                                    DocumentsContract.buildChildDocumentsUriUsingTree(
                                        directory,
                                        DocumentsContract.getDocumentId(directory),
                                    )
                                context.contentResolver.registerContentObserver(
                                    children,
                                    false,
                                    observer,
                                )
                                observed = children
                            }
                    },
                    {
                        entries = emptyList()
                        render()
                        status.text = it.message ?: "Cannot open folder"
                    },
                )
            }
        }
    }

    private fun relay(directory: Uri?): List<Entry> {
        val token = generation
        val listing = FolderStorage.list(context, directory)
        if (listing.loading) main.postDelayed({ if (token == generation) refresh() }, 1000)
        return listing.entries.map {
            Entry(it.uri, it.name, it.directory, it.mime, it.size, it.label, it.modified, true)
        }
    }

    private fun privateLocation(uri: Uri): Boolean =
        uri.scheme == "file" &&
            runCatching {
                    val path = File(uri.path!!).canonicalPath
                    path.startsWith(context.filesDir.canonicalPath + "/") ||
                        path.startsWith(context.cacheDir.canonicalPath + "/")
                }
                .getOrDefault(false)

    private fun roots(): List<Entry> {
        val out = mutableListOf<Entry>()
        if (FolderStorage.available(context)) out.addAll(relay(null))
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .forEach {
                out +=
                    Entry(
                        document(it.uri),
                        "Android storage",
                        true,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        null,
                    )
            }
        out += Entry(Uri.parse("infiniselect://add-storage"), "Add storage folder…", true, "", null)
        out += Entry(Uri.parse("infiniselect://open"), "Android file picker…", true, "", null)
        return out.distinctBy { it.uri }
    }

    private fun document(tree: Uri) =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun list(directory: Uri): List<Entry> {
        if (!privateLocation(directory) && FolderStorage.available(context))
            return sorted(relay(directory))
        val result =
            if (directory.scheme == "file") {
                val file = File(requireNotNull(directory.path))
                require(file.isDirectory && file.canRead()) {
                    "Allow internal storage access to browse this directory"
                }
                requireNotNull(file.listFiles()) { "Cannot list directory" }
                    .filter { !it.name.startsWith(".") }
                    .map {
                        val uri = Uri.fromFile(it)
                        val label =
                            if (it.extension.equals("iflink", true))
                                runCatching {
                                        FileShortcut.decode(it.readTextLimited()).displayName
                                    }
                                    .getOrDefault(it.name)
                            else it.name
                        Entry(
                            uri,
                            it.name,
                            it.isDirectory,
                            if (it.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                            else "application/octet-stream",
                            if (it.isFile) it.length() else null,
                            label,
                            it.lastModified(),
                        )
                    }
            } else {
                require(DocumentsContract.isTreeUri(directory)) {
                    "Select a storage folder to grant browsing access"
                }
                val uri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        directory,
                        DocumentsContract.getDocumentId(directory),
                    )
                val projection =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    )
                val rows = mutableListOf<Entry>()
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val name = cursor.getString(1)
                        val mime = cursor.getString(2) ?: "application/octet-stream"
                        rows +=
                            Entry(
                                DocumentsContract.buildDocumentUriUsingTree(directory, id),
                                name,
                                mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                mime,
                                if (cursor.isNull(3)) null else cursor.getLong(3),
                            )
                    }
                } ?: error("Storage provider returned no directory listing")
                rows
            }
        return sorted(result)
    }

    private fun sorted(result: List<Entry>): List<Entry> {
        val files =
            if (sortByModified)
                compareByDescending<Entry> { it.modified }.thenBy { it.label.lowercase() }
            else compareBy<Entry> { it.label.lowercase() }
        return result.sortedWith(compareBy<Entry> { !it.directory }.then(files))
    }

    private fun File.readTextLimited(): String =
        inputStream().use {
            val bytes = it.readShortcutBytes()
            require(bytes.size <= FileShortcut.MAX_BYTES)
            String(bytes, Charsets.UTF_8)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) render() else surface?.updateViewport(scroll.scrollY, scroll.height)
    }

    private fun render() {
        val revision = ++renderGeneration
        artworkCache.evictAll()
        toggle.text = if (iconMode) "☷ List" else "▦ Icons"
        toggle.contentDescription = if (iconMode) "Switch to list view" else "Switch to icon view"
        setBackgroundColor(appearance.background)
        path.setTextColor(appearance.textPrimary)
        status.setTextColor(appearance.textSecondary)
        surface = null
        content.removeAllViews()
        val density = resources.displayMetrics.density
        val iconDp = 56
        val widthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val columns = if (iconMode) FileViewGrid.columns((widthPx / density).toInt(), iconDp) else 1
        val cellWidth = widthPx / columns
        val size = ((if (iconMode) iconDp else 44) * density).toInt().coerceIn(16, 256)
        val makeCard: (Entry) -> View = { entry ->
            val card =
                FileEntryCard(
                    context,
                    FileCardEntry(entry.directory, shortcut = entry.name.endsWith(".iflink", true)),
                    iconMode,
                    iconDp,
                    appearance.isDark,
                    cardTheme = appearance,
                )
            val image =
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(
                        if (entry.directory) android.R.drawable.ic_menu_agenda
                        else android.R.drawable.ic_menu_save
                    )
                    contentDescription = if (entry.directory) "Folder" else "File"
                }
            card.attachIcon(image)
            card.addLabels(
                entry.label,
                entry.directory,
                (cellWidth - 4 * density).toInt().coerceAtLeast(36),
                if (entry.directory) "Folder"
                else
                    entry.size
                        ?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                        .orEmpty(),
            )
            card.container.setOnClickListener { activate(entry) }
            card.container.setOnLongClickListener { anchor ->
                if (!entry.directory) {
                    select(entry, true, anchor)
                    true
                } else false
            }
            // Artwork stays in Folder's resolver; no source or permission crosses into the host.
            if (entry.relayed) {
                val key =
                    "${entry.uri}:${entry.modified}:${entry.size}:$size:${appearance.hashCode()}"
                fun apply(art: FolderStorage.Artwork) {
                    image.setImageBitmap(art.bitmap)
                    if (entry.name.endsWith(".iflink", true) && art.label.isNotBlank())
                        card.nameView.setFileName(art.label, art.directory)
                }
                artworkCache.get(key)?.let(::apply)
                    ?: run {
                        if (artworkExecutor.isShutdown)
                            artworkExecutor = Executors.newFixedThreadPool(2)
                        artworkExecutor.execute {
                            if (revision != renderGeneration) return@execute
                            val art =
                                runCatching { FolderStorage.artwork(context, entry.uri, size) }
                                    .getOrNull()
                            if (art != null && revision == renderGeneration) {
                                artworkCache.put(key, art)
                                main.post {
                                    if (
                                        revision == renderGeneration &&
                                            card.container.isAttachedToWindow
                                    )
                                        apply(art)
                                }
                            }
                        }
                    }
            }
            card.container
        }
        if (entries.size > 48) {
            surface =
                VirtualizedEntrySurface(
                    context,
                    entries,
                    columns,
                    ((if (iconMode) iconDp + 48 else 64) * density).toInt(),
                    makeCard,
                )
            content.addView(surface)
            scroll.post { surface?.updateViewport(scroll.scrollY, scroll.height) }
        } else {
            content.addView(FileViewGrid.build(context, entries, columns, card = makeCard))
        }
    }

    private fun activate(entry: Entry) {
        when (entry.uri.toString()) {
            "infiniselect://folder-access" ->
                onRequestTree(
                    DocumentsContract.buildDocumentUri(
                        FileSelectionContract.INFINIFOLDER_DOCUMENTS_AUTHORITY,
                        "root",
                    )
                )
            "infiniselect://add-storage" -> onRequestTree(null)
            "infiniselect://open" -> onRequestOpen()
            else ->
                if (entry.directory) {
                    if (
                        entry.uri.scheme == "file" &&
                            !entry.relayed &&
                            !privateLocation(entry.uri) &&
                            !canBrowseLocal()
                    )
                        onRequestLocalAccess()
                    else {
                        current = entry.uri
                        trail.add(entry.uri to entry.label)
                        scroll.scrollTo(0, 0)
                        refresh()
                    }
                } else select(entry, false)
        }
    }

    private fun select(entry: Entry, menu: Boolean, anchor: android.view.View? = null) {
        val token = generation
        executor.execute {
            val result = runCatching {
                val source =
                    if (entry.relayed)
                        InfiniSelect.requireSource(
                            context,
                            entry.uri.toString(),
                            entry.name,
                            entry.mime,
                            entry.size,
                        )
                    else null
                val access =
                    source?.contentUri
                        ?: if (entry.uri.scheme == "file")
                            InfiniSelect.createFileDocument(
                                context,
                                File(entry.uri.path!!),
                                entry.name,
                                entry.mime,
                            )
                        else entry.uri
                val identity =
                    InfiniSelect.authoritativeUri(context, entry.uri.toString()) ?: entry.uri
                SelectionItem(
                    access,
                    entry.name,
                    entry.mime,
                    entry.size,
                    sourceReference = identity.toString(),
                )
            }
            main.post {
                if (token == generation)
                    result.fold(
                        { item ->
                            if (!menu) onSelected(item)
                            else {
                                val actions =
                                    runCatching { SelectionAction.validate(fileActions(item)) }
                                        .getOrElse {
                                            onError(it.message ?: "Invalid file actions")
                                            return@fold
                                        }
                                if (actions.isEmpty()) onFileMenu(item)
                                else
                                    PopupMenu(context, anchor ?: this)
                                        .apply {
                                            this.menu.add(0, 0, 0, "Open")
                                            actions.forEachIndexed { index, action ->
                                                this.menu.add(0, index + 1, index + 1, action.label)
                                            }
                                            setOnMenuItemClickListener { selected ->
                                                if (selected.itemId == 0) onSelected(item)
                                                else
                                                    actions.getOrNull(selected.itemId - 1)?.let {
                                                        onFileAction(it.id, item)
                                                    }
                                                true
                                            }
                                        }
                                        .show()
                            }
                        },
                        { onError(it.message ?: "Cannot open file") },
                    )
            }
        }
    }

    override fun onDetachedFromWindow() {
        ++generation
        ++renderGeneration
        artworkExecutor.shutdownNow()
        observed?.let { context.contentResolver.unregisterContentObserver(observer) }
        observed = null
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
