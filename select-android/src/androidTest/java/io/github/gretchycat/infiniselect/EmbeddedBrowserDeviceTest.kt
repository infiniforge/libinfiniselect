package io.github.gretchycat.infiniselect

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device acceptance against an installed Folder; this must exercise a real cross-UID surface. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class EmbeddedBrowserDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun node(text: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.packageName?.toString() == "com.infinifolder" }

    private fun click(text: String) {
        var target = requireNotNull(node(text))
        repeat(8) {
            if (target.isClickable) {
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            target = target.parent ?: error("No clickable Folder parent for $text")
        }
        error("No clickable Folder parent for $text")
    }

    @Test
    fun folderOwnsBreadcrumbsNavigationAndSelectedFileGrant() {
        val context = instrumentation.targetContext
        assumeTrue("Install the updated InfiniFolder APK first", FolderStorage.available(context))
        val source =
            File(context.cacheDir, "embedded-source.gb").apply {
                writeText("original test fixture")
            }
        val sourceUri =
            InfiniSelect.createFileDocument(
                context,
                source,
                source.name,
                "application/octet-stream",
            )
        context.grantUriPermission(
            "com.infinifolder",
            sourceUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val collections = ShortcutCollections(context, "browser-device-test")
        val shortcut = FileShortcut(sourceUri.toString(), "Embedded source.gb")
        val directory = collections.directory("recents", "test")
        collections.put("recents", "test", shortcut)
        var activity: BrowserHostTestActivity? = null
        try {
            activity =
                instrumentation.startActivitySync(
                    Intent(context, BrowserHostTestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("directory", directory.toString())
                ) as BrowserHostTestActivity
            val host = activity
            await("The actual Folder filename view was not embedded") {
                node("Embedded source.gb") != null
            }
            await("Folder did not expose its own breadcrumb trail") { node("Virtual root") != null }
            click("Embedded source.gb")
            await("Folder selection did not reach its caller") { host.selected != null }
            val selected = requireNotNull(host.selected)
            assertEquals("content", selected.contentUri.scheme)
            assertEquals(
                directory.toString(),
                selected.authoritativeUri.toString().substringBeforeLast('/'),
            )
            assertEquals(
                shortcut.targetUri,
                FileShortcut.read(context, selected.contentUri).targetUri,
            )
            // The same browser session remains usable after a file selection.
            click("Virtual root")
            await("Folder breadcrumb navigation did not change the location") {
                host.browser.location() != directory
            }
            val rootLocation = host.browser.location()
            val readyBefore = host.readyCount
            instrumentation.runOnMainSync {
                val parent = host.browser.parent as android.view.ViewGroup
                parent.removeView(host.browser)
                parent.addView(host.browser)
            }
            await("Folder surface was not re-created") { host.readyCount > readyBefore }
            await("Folder did not restore its own browser state") {
                host.browser.location() == rootLocation
            }
            assertNull(host.failure)
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            collections.remove("recents", "test", shortcut)
            context.revokeUriPermission(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source.delete()
        }
    }
}

class BrowserHostTestActivity : Activity() {
    lateinit var browser: EmbeddedFileBrowser
    @Volatile var readyCount = 0
    @Volatile var selected: SelectionItem? = null
    @Volatile var failure: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        browser =
            EmbeddedFileBrowser(this, FileViewMode.LIST).apply {
                selectLinkDocuments = true
                onReady = { readyCount++ }
                onSelected = { selected = it }
                onError = { failure = it }
            }
        setContentView(browser)
        browser.open(Uri.parse(intent.getStringExtra("directory")), "Browser test")
    }
}
