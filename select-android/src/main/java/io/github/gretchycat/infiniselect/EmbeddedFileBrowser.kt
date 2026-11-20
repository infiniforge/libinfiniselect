package io.github.gretchycat.infiniselect

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.*
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import java.util.concurrent.Executors

/**
 * Gateway to Folder's complete browser, running in Folder's process. This View only hosts its
 * remote surface and relays commands/results. Folder owns every breadcrumb, file card, menu,
 * listing, gesture and navigation operation.
 */
class EmbeddedFileBrowser
@JvmOverloads
constructor(context: Context, private val preferredViewMode: FileViewMode? = null) :
    FrameLayout(context) {
    var onReady: () -> Unit = {}
    var onSelected: (SelectionItem) -> Unit = {}
    var onFileAction: (String, SelectionItem) -> Unit = { _, _ -> }
    var onRequestOpen: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var fileActions: List<SelectionAction> = emptyList()
        set(value) {
            field = SelectionAction.validate(value)
            configure()
        }

    var breadcrumbsEnabled =
        context.getSharedPreferences("infiniselect.embedded", 0).getBoolean("breadcrumbs", true)
        set(value) {
            field = value
            context
                .getSharedPreferences("infiniselect.embedded", 0)
                .edit()
                .putBoolean("breadcrumbs", value)
                .apply()
            configure()
        }

    var selectLinkDocuments = false
        set(value) {
            field = value
            configure()
        }

    var sortByModified = false
        set(value) {
            field = value
            configure()
        }

    private val main = Handler(Looper.getMainLooper())
    private val lifetime = Binder()
    @Volatile private var generation = 0
    @Volatile private var openVersion = 0
    private var sessionOpenVersion = -1
    private var savedVersion = -1
    private var recoveryAttemptVersion = -1
    private var savedState: Bundle? = null
    private var initialDirectory: Uri? = null
    private var lockToDirectory = false
    private var initialLabel = "Virtual root"
    private var current: Uri? = null
    private var canGoBack = false
    private var ready = false
    // Worker-owned connection; held for the full embedded lifetime to keep Folder alive.
    private var remote: ContentProviderClient? = null
    private var session: String? = null
    private var server: IBinder? = null
    private var serverDeath: IBinder.DeathRecipient? = null
    private var surface: SurfaceView? = null
    private val fallback =
        Button(context).apply {
            text = "Open files"
            isAllCaps = false
            contentDescription = "Open the file selector"
            setOnClickListener { onRequestOpen() }
        }

    init {
        addView(fallback, LayoutParams(-1, -2))
        if (Build.VERSION.SDK_INT >= 30) installSurface()
    }

    @android.annotation.TargetApi(30)
    private fun installSurface() {
        // Android only delivers input to the embedded hierarchy above the host window.
        val view = SurfaceView(context).apply { setZOrderOnTop(true) }
        surface = view
        addView(view, LayoutParams(-1, -1))
        view.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (width <= 0 || height <= 0) return
                    if (ready)
                        command(
                            EmbeddedBrowserProtocol.RESIZE,
                            Bundle().apply {
                                putInt(EmbeddedBrowserProtocol.WIDTH, width)
                                putInt(EmbeddedBrowserProtocol.HEIGHT, height)
                            },
                        )
                    else connect(view, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    disconnect()
                }
            }
        )
    }

    @JvmOverloads
    fun open(
        directory: Uri? = null,
        label: String = "Virtual root",
        lockToDirectory: Boolean = false,
    ) {
        require(!lockToDirectory || directory != null) {
            "A locked browser needs a starting directory"
        }
        this.lockToDirectory = lockToDirectory
        ++openVersion
        initialDirectory = directory
        initialLabel = label
        current = directory
        canGoBack = false
        if (ready) command(EmbeddedBrowserProtocol.OPEN, request()) else reconnect()
    }

    fun location(): Uri? = current

    fun goBack(): Boolean {
        if (!ready || !canGoBack) return false
        command(EmbeddedBrowserProtocol.BACK)
        return true
    }

    fun refresh() {
        if (ready) command(EmbeddedBrowserProtocol.REFRESH) else reconnect()
    }

    @android.annotation.TargetApi(30)
    private fun reconnect() {
        val view = surface ?: return
        if (!view.isAttachedToWindow) return
        view.visibility = VISIBLE
        view.post {
            if (ready || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return@post
            if (view.holder.surface.isValid) connect(view, view.width, view.height)
        }
    }

    private fun recover(message: String) {
        if (recoveryAttemptVersion == openVersion) {
            fail(message)
            return
        }
        recoveryAttemptVersion = openVersion
        ready = false
        reconnect()
    }

    private fun request() =
        Bundle().apply {
            putString(EmbeddedBrowserProtocol.DIRECTORY, initialDirectory?.toString())
            putString(EmbeddedBrowserProtocol.LABEL, initialLabel)
            putBoolean(EmbeddedBrowserProtocol.LOCK_ROOT, lockToDirectory)
            putBoolean(EmbeddedBrowserProtocol.BREADCRUMBS, breadcrumbsEnabled)
            putBoolean(EmbeddedBrowserProtocol.SORT_MODIFIED, sortByModified)
            putBoolean(EmbeddedBrowserProtocol.SELECT_LINK_DOCUMENTS, selectLinkDocuments)
            preferredViewMode?.let {
                putString(FileSelectionContract.EXTRA_PREFERRED_VIEW_MODE, it.name)
            }
            putStringArrayList(
                FileSelectionContract.EXTRA_FILE_ACTION_IDS,
                ArrayList(fileActions.map { it.id }),
            )
            putStringArrayList(
                FileSelectionContract.EXTRA_FILE_ACTION_LABELS,
                ArrayList(fileActions.map { it.label }),
            )
        }

    private fun configure() {
        if (ready) command(EmbeddedBrowserProtocol.CONFIGURE, request())
    }

    @android.annotation.TargetApi(30)
    @Suppress("DEPRECATION")
    private fun connect(view: SurfaceView, width: Int, height: Int) {
        val token = ++generation
        val requestedOpenVersion = openVersion
        ready = true // coalesce layout callbacks while creation is pending
        val args =
            request().apply {
                putInt("version", EmbeddedBrowserProtocol.VERSION)
                putString(EmbeddedBrowserProtocol.ACTION, EmbeddedBrowserProtocol.CREATE)
                putBinder(EmbeddedBrowserProtocol.HOST_TOKEN, view.hostToken)
                putBinder(EmbeddedBrowserProtocol.LIFETIME, lifetime)
                putInt(EmbeddedBrowserProtocol.WIDTH, width)
                putInt(EmbeddedBrowserProtocol.HEIGHT, height)
                putInt(EmbeddedBrowserProtocol.DISPLAY, view.display.displayId)
                putParcelable(
                    EmbeddedBrowserProtocol.RECEIVER,
                    object : ResultReceiver(main) {
                            override fun onReceiveResult(code: Int, data: Bundle?) {
                                if (token != generation || data == null) return
                                when (code) {
                                    EmbeddedBrowserProtocol.NAVIGATION -> {
                                        current =
                                            data
                                                .getString(EmbeddedBrowserProtocol.DIRECTORY)
                                                ?.let(Uri::parse)
                                        canGoBack =
                                            data.getBoolean(EmbeddedBrowserProtocol.CAN_GO_BACK)
                                    }
                                    EmbeddedBrowserProtocol.ERROR -> {
                                        val message =
                                            data.getString("message") ?: "Folder browser unavailable"
                                        if (message.contains("unavailable", true) ||
                                            message.contains("disconnected", true)
                                        ) recover(message) else fail(message)
                                    }
                                    FileSelectionContract.RESULT_SELECTED -> {
                                        val uri =
                                            data
                                                .getString(FileSelectionContract.EXTRA_RESULT_URI)
                                                ?.let(Uri::parse) ?: return
                                        val item =
                                            SelectionItem(
                                                uri,
                                                data
                                                    .getString(
                                                        FileSelectionContract.EXTRA_RESULT_NAME
                                                    )
                                                    .orEmpty(),
                                                data.getString(
                                                    FileSelectionContract.EXTRA_RESULT_MIME
                                                ) ?: "application/octet-stream",
                                                data
                                                    .getLong(
                                                        FileSelectionContract.EXTRA_RESULT_SIZE,
                                                        -1L,
                                                    )
                                                    .takeIf { it >= 0 },
                                                sourceReference =
                                                    data.getString(EmbeddedBrowserProtocol.SOURCE),
                                            )
                                        val action =
                                            data.getString(
                                                FileSelectionContract.EXTRA_RESULT_FILE_ACTION
                                            )
                                        if (action == null) onSelected(item)
                                        else if (fileActions.any { it.id == action })
                                            onFileAction(action, item)
                                    }
                                }
                            }
                        }
                        .forIpc(),
                )
            }
        calls.execute {
            if (token != generation) return@execute
            try {
                closeRemote()
                val client =
                    context.contentResolver.acquireUnstableContentProviderClient(
                        Uri.parse(
                            "content://" + FileSelectionContract.INFINIFOLDER_SELECTION_AUTHORITY
                        )
                    ) ?: error("Install or update InfiniFolder to embed its browser")
                remote = client
                if (savedVersion == requestedOpenVersion)
                    args.putBundle(EmbeddedBrowserProtocol.STATE, savedState)
                sessionOpenVersion = requestedOpenVersion
                val reply =
                    client.call(EmbeddedBrowserProtocol.METHOD, null, args)
                        ?: error("Update InfiniFolder to embed its browser")
                require(reply.getInt("version") == EmbeddedBrowserProtocol.VERSION)
                session = requireNotNull(reply.getString(EmbeddedBrowserProtocol.SESSION))
                server = requireNotNull(reply.getBinder(EmbeddedBrowserProtocol.SERVER))
                serverDeath =
                    IBinder.DeathRecipient {
                        main.post {
                            if (token == generation) {
                                disconnect()
                                surface?.visibility = GONE
                                fallback.visibility = VISIBLE
                            }
                        }
                    }
                server!!.linkToDeath(serverDeath!!, 0)
                val pkg =
                    reply.getParcelable<SurfaceControlViewHost.SurfacePackage>(
                        EmbeddedBrowserProtocol.SURFACE
                    ) ?: error("InfiniFolder did not return its browser surface")
                main.post {
                    if (token != generation || !view.holder.surface.isValid) pkg.release()
                    else {
                        runCatching { view.setChildSurfacePackage(pkg) }
                            .onSuccess {
                                recoveryAttemptVersion = -1
                                fallback.visibility = GONE
                                onReady()
                            }
                            .onFailure {
                                pkg.release()
                                disconnect()
                                fail(it.message ?: "Cannot attach Folder browser")
                            }
                    }
                }
            } catch (error: Exception) {
                closeRemote()
                main.post {
                    if (token == generation) fail(error.message ?: "Folder browser unavailable")
                }
            }
        }
    }

    private fun command(action: String, args: Bundle = Bundle()) {
        val token = generation
        val requestedOpenVersion = openVersion
        calls.execute {
            if (token != generation) return@execute
            val id = session ?: return@execute
            try {
                remote?.call(
                    EmbeddedBrowserProtocol.METHOD,
                    null,
                    args.apply {
                        putString(EmbeddedBrowserProtocol.ACTION, action)
                        putString(EmbeddedBrowserProtocol.SESSION, id)
                    },
                )
                if (action == EmbeddedBrowserProtocol.OPEN) {
                    sessionOpenVersion = requestedOpenVersion
                    savedState = null
                    savedVersion = -1
                }
            } catch (error: Exception) {
                closeRemote()
                main.post {
                    if (token == generation) {
                        val message = error.message ?: "Folder disconnected"
                        if (action == EmbeddedBrowserProtocol.REFRESH) recover(message)
                        else fail(message)
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        ready = false
        surface?.visibility = GONE
        fallback.visibility = VISIBLE
        onError(message)
    }

    private fun closeRemote() {
        val client = remote
        val id = session
        val death = serverDeath
        if (death != null) runCatching { server?.unlinkToDeath(death, 0) }
        server = null
        serverDeath = null
        remote = null
        session = null
        try {
            if (id != null) {
                val reply =
                    client?.call(
                        EmbeddedBrowserProtocol.METHOD,
                        null,
                        Bundle().apply {
                            putString(EmbeddedBrowserProtocol.ACTION, EmbeddedBrowserProtocol.CLOSE)
                            putString(EmbeddedBrowserProtocol.SESSION, id)
                        },
                    )
                savedState = reply?.getBundle(EmbeddedBrowserProtocol.STATE)
                savedVersion = sessionOpenVersion
            }
        } catch (_: Exception) {} finally {
            client?.close()
        }
    }

    private fun disconnect() {
        ++generation
        ready = false
        canGoBack = false
        calls.execute { closeRemote() }
    }

    override fun onDetachedFromWindow() {
        disconnect()
        super.onDetachedFromWindow()
    }

    companion object {
        private val calls =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "InfiniSelect-browser").apply { isDaemon = true }
            }
    }
}
