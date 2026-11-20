# Embedding the complete InfiniFolder browser

InfiniSelect 0.2.3 is a gateway to InfiniFolder's complete existing browser. It does
not implement file listing UI, cards, breadcrumbs, directory history/navigation,
sorting, gestures, thumbnails or file menus. Those views and behavior remain in
InfiniFolder. Its floating selector and embedded sessions use the same
FolderBrowserController, which was moved intact from the working floating service.
The earlier partial file-card extraction and second browser have been removed.

```kotlin
val browser = EmbeddedFileBrowser(activity, FileViewMode.LIST).apply {
    fileActions = listOf(SelectionAction("add_favorite", "Add to favorites"))
    onSelected = { item -> openDocument(item) }
    onFileAction = { id, item -> handleAppAction(id, item) }
    onRequestOpen = { openNormalInfiniSelectPicker() }
    onError = { message -> showError(message) }
}
container.addView(browser)
browser.open() // Folder's actual Virtual root, including its breadcrumb trail
```

On Android 11/API 30 and newer, Folder renders and handles input in its own
process through Android's SurfaceControlViewHost. The gateway hosts that surface
and forwards open, resize, back, refresh and configuration commands. A retained
unstable ContentProviderClient keeps the provider connection alive without making
a Folder crash terminate the client; a server Binder death callback exposes a
fallback. Host detach/surface destruction closes the session. Folder returns an opaque, validated trail/scroll snapshot that the gateway passes back on reattach; an explicit open discards the prior snapshot. Only Folder interprets this state. `onReady` reports attachment of the new surface. A caller Binder
death recipient cleans up after a host process dies. Session ownership is checked
against the actual Binder UID, with per-caller/global limits. Selected files get
normal read-granted relays, retaining their authoritative source separately.
Neither an overlay permission nor a storage permission is needed in the client.

An updated installed Folder is required for in-app embedding. Without it, or on
Android 8–10, this View offers a single Open files action through `onRequestOpen`,
which the host wires to ordinary InfiniSelect/Android selection. There is no
replacement file browser. Apps remain independently buildable/installable.

`open(directory, label, lockToDirectory = true)` asks Folder to use that directory
as its top level. Breadcrumbs, Back, saved surface state and load-error recovery
stay anchored there; children remain browsable. The default is unrestricted
Virtual root navigation. Open/Browse should use `open()`; Recents/Favorites can
lock to their respective collection roots. This is a navigation boundary, not a
replacement for storage permissions. The optional version-1 `lockRoot` field
requires Folder 0.1.9+; older Folder builds ignore it.

The hosting SurfaceView is above its host window so Android can deliver touch
to Folder. Folder uses a flat, unelevated embedded root to avoid floating-window
shadow insets and offset content. Floating-window appearance remains Folder-owned.

`breadcrumbsEnabled` controls Folder's whole browser toolbar, including the
list/icon toggle. Folder owns the actual trail and its clickable ancestors.
`preferredViewMode` optionally initializes LIST/ICON; absent it, Folder uses its
saved preference. Folder handles user toggles, pinch sizing, pull-to-refresh,
source navigation, archive handling, artwork and provider changes. `goBack()`
forwards Back when Folder's last navigation event says a parent is available;
`location()` returns only its reported current URI. The gateway stores no trail.
`sortByModified` requests Folder's ordering for recent collections. Setting
`selectLinkDocuments=true` asks Folder's selection policy to return .iflink
files so hosts such as Play can read their own extension metadata; default
selection follows links using Folder's existing resolver.

Folder resolves shortcut targets before deciding what to do, regardless of where
an .iflink is stored. Directories and browsable archives navigate inside Folder;
other URI targets go through Folder's existing URI dispatcher. For regular-file
targets only, `selectLinkDocuments=true` returns the original shortcut so a
caller can preserve its own optional metadata. This option does not turn folder,
archive, web or custom URI links into file-import requests.

`fileActions: List<SelectionAction>` supplies long-press labels/IDs to Folder,
which renders the menu inside its hosted view. `onFileAction(id, item)` dispatches
the caller's choice; the caller owns Favorites/metadata side effects. A selection
or file action leaves the embedded session open. Floating selection retains its
existing one-result lifecycle and `OpenRequest.fileActions` contract.

Compatibility: 0.2.0 replaces the experimental 0.1.x local browser and renderer
classes. Embedded fileActions is now a list supplied when the host changes its
page, matching floating requests. Local renderer, entry, tree-access and whole-menu
callbacks were removed. Android SAF is the non-embedded fallback, not a second
browser in the library.

## Embedding wire contract

`ContentResolver.call` on `com.infinifolder.selection` uses method
`io.github.gretchycat.infiniselect.EMBED_BROWSER`, protocol version 1. Create
passes display, dimensions, host input token, caller lifetime Binder,
a platform `android.os.ResultReceiver`, initial directory/label and selection
configuration. The gateway normalizes callback subclasses through Android's
ResultReceiver CREATOR before placing them in a Bundle. Only the Binder endpoint
crosses processes; Folder never has to load a caller's private callback class.
The same normalization applies to ordinary picker progress callbacks. This fixes
the 0.2.0 unmarshalling failure without changing protocol version 1. Creation returns
an opaque session ID, server Binder and SurfacePackage. Subsequent commands carry
the session ID and run only for the owning UID. Dimensions are bounded at 8192 px;
at most four sessions per UID and sixteen globally are accepted. Session state
and complete View hierarchies stay in Folder; metadata navigation events carry
only the current directory and whether Back is available.

## File links

`FileShortcut` reads/writes version-1 `.iflink` JSON with `targetUri`, `displayNameOverride`, `targetMimeType`, `targetIsDirectory`, `createdAtMillis`, plus optional `infiniPlaySystem`. Documents are bounded to 128 KiB. Directory targets are preserved, and valid absolute URIs have no scheme whitelist. Temporary Folder access relays and unknown versions are rejected. App-specific game validation belongs in Play, not in this model. `writeTo(directory)` atomically creates a SHA-256-named file or reuses an existing same-identity link, including user-named links. Client apps should use `ShortcutCollections` for shared directories; only the storage-owning process writes those files. Removal never deletes the target.

## Validation

The standalone AAR/JVM and Android instrumentation APK builds compile the gateway.
`ResultReceiverTransportTest` round-trips browser/progress callbacks using a
platform-only class loader and verifies callback payloads and main-handler
execution. It needs an Android device but no installed Folder or storage access.
`EmbeddedBrowserDeviceTest` exercises an installed Folder through a real surface:
Folder-owned filename/breadcrumb accessibility nodes, file selection and read
grants, breadcrumb navigation after selection and surface re-creation with Folder-owned state restoration. It requires an attached
Android 11+ device with updated Folder and Folder storage access. Compilation is
not execution. Also check list/icon preferences, long-press Favorites actions,
rotation/resizing, detach/recreate, provider/host process death and pre-11/absent
Folder fallback on hardware.

Android API reference: [SurfaceControlViewHost](https://developer.android.com/reference/android/view/SurfaceControlViewHost).
