# libinfiniselect

`libinfiniselect` is an Android library for viewer apps that need an open-file
flow without depending on InfiniFolder's UI or storage implementation.

## Embedded browser

Version 0.2.3 embeds the complete existing InfiniFolder browser through an Android surface. Folder owns all file views, breadcrumbs, directory navigation, menus, gestures and artwork; InfiniSelect only hosts the surface and forwards commands/results. In-app embedding requires Android 11+ and updated Folder. Other configurations use ordinary file selection, with no second browser implementation. The JSON directory/collection APIs remain available independently. See [the browser gateway](docs/embedded-browser.md) and [relay API](docs/relay-storage.md).

## Use

```kotlin
InfiniSelect.open(
    activity,
    OpenRequest(
        clientId = "infiniscribe",
        displayName = "InfiniScribe",
        mimeTypes = listOf("*/*"),
        allowMultiple = false,
    ),
    REQUEST_OPEN
)
```

Handle the Activity result as a normal Android document result. The returned
intent carries a readable `content://` URI, a display name, and MIME type. Grants
received from the provider belong to the consuming application's UID; the internal
selector returns the result without granting them again. Only access links with
an actual grant enter `ClipData`; remembered directories and authoritative
references remain metadata. InfiniFolder's URI is a short-lived read-only relay, so
remote, archive, and provider-backed files can be opened without exposing
storage credentials or private VFS schemes to the viewer.

When InfiniFolder is installed, the library launches its floating selector at
Virtual root. Otherwise it shows the requesting app's icon/name, installed
source buttons, Android's Storage Access Framework picker, and a per-client
default starting folder. Optional `FileSourceBackend` implementations can be
registered at runtime or exposed with `ServiceLoader` through
`META-INF/services/io.github.gretchycat.infiniselect.FileSourceBackend`.

Set `newFileType` on `OpenRequest` to let the caller create a new document in a
selected directory:

```kotlin
InfiniSelect.open(activity, OpenRequest(
    clientId = "infiniscribe",
    displayName = "InfiniScribe",
    mimeTypes = listOf("text/markdown"),
    supportedExtensions = listOf("md"),
    newFileType = NewFileType("text/markdown", "md"),
), REQUEST_OPEN)
```

Read the result with `InfiniSelect.readResult(...)`. When `isNewFile` is true,
`items` is empty and the directory fields identify where the caller can save its
new document. The caller owns the empty document model and its editor state.

The library uses Android/Kotlin APIs only; it contains no native code and can
be built with Android's supported target architectures. It does not depend on
InfiniFolder. The optional InfiniFolder integration is a documented intent
contract in `FileSelectionContract`. Its picker starts at Virtual root as
requested. Multiple selection is supported by the InfiniFolder selector and
the Android SAF fallback.

Every selected item has two values with different jobs:

- `SelectionItem.authoritativeUri` is its durable resource identity. It can be
  a local file URI, a SAF document URI, an SFTP address, or an opaque
  archive/filesystem-image URI. Keep it for playlists and later requests.
- `SelectionItem.contentUri` is the Android URI that currently grants access
  to the bytes. InfiniFolder commonly returns a temporary stream relay. Read
  through it while its grant is valid; never save it as the resource identity.

`FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URI` and
`EXTRA_RESULT_AUTHORITATIVE_URIS` carry identities separately from
`EXTRA_RESULT_CONTENT_URI` / `EXTRA_RESULT_CONTENT_URIS`. The older `uri` and
`sourceReference` properties remain for source compatibility; new code should
use the explicit properties. SAF document URIs can be both identity and access
URI because the document URI is the provider's canonical resource address.
This does not make an InfiniFolder relay URI durable.

## Creating a new document

An editor can opt in to creating a new document of a known type by setting
`OpenRequest.newFileType`. In InfiniFolder's floating selector, long-press a
directory and choose **New**. The result contains no selected file: use
`InfiniSelect.readResult(...)` and check `SelectionResult.isNewFile`, then create
a blank document in the caller's own editing model. The result supplies the
selected directory as both a current content URI and an authoritative location,
plus the requested MIME type and optional extension. InfiniSelect does not write
an empty placeholder file before the editor has content to save.

When InfiniFolder is unavailable, the source chooser offers **New file in
folder**. That uses Android's SAF directory picker and returns the same new-file
result shape with the tree URI and the grants SAF actually issued. When the
request also asks for writable existing documents, the chooser offers both
InfiniFolder for new-file creation and Android files for editable documents.

Send the generated output back through `InfiniSelect.save`. A caller-owned file
can be shared as a temporary readable URI without copying it:

```kotlin
val source = InfiniSelect.createFileDocument(
    context, draftFile, name = "notes.md", mimeType = "text/markdown"
)
InfiniSelect.save(activity, SaveRequest(
    clientId = "infiniscribe",
    displayName = "InfiniScribe",
    fileName = "notes.md",
    mimeType = "text/markdown",
    sourceUri = source,
    sourceSizeBytes = draftFile.length(),
    initialDirectory = selection.directoryAuthoritativeUri,
), REQUEST_SAVE)
```

The temporary file link remains readable for up to 24 hours, so keep the source
file until the save result returns. With InfiniFolder installed, the save picker
writes through its storage provider. Without it, InfiniSelect uses Android's
`ACTION_CREATE_DOCUMENT` flow and copies the generated bytes into the SAF
location selected by the user.

Resolve a saved location only when its bytes are needed:

```kotlin
val opened = InfiniSelect.requireSource(context, savedAuthoritativeUri.toString())
// Open opened.contentUri; persist opened.authoritativeUri.
```

When a selection still includes its current access URI, pass both values to
`requireSelectedSource(context, contentUri, authoritativeUri, ...)`. The library
keeps that readable grant for the current read and asks InfiniFolder for a fresh
link only when the current link is no longer readable. This matters for SAF
identities whose provider URI is canonical but whose permission was granted to
the current temporary relay instead.

The `requireSource` and `requireSelectedSource` methods throw
`SourceResolutionException` when access cannot be established and retain the
underlying provider error. Run them on a worker thread. A resolution failure
does not prove that the file is invalid; players should retain the queue and
report the error. Nullable `resolveSource` and `resolveSelectedSource` methods
remain available for callers that explicitly handle unavailable sources.
Remote saved locations require an installed InfiniFolder build that implements
`STORAGE_METHOD_RESOLVE_SOURCE`; upgrading only a client does not update that
optional storage service.

This library call delegates remote, archive, and filesystem-image resolution
to the optional InfiniFolder IPC provider. Callers do not parse backend URI
syntax. `ResolvedSource` always keeps the access URI and authoritative
location separate. An expired relay cannot recover a source identity unless
the relay is still valid; retain the authoritative URI from the selection.

For a readable `file://` source when InfiniFolder is unavailable,
libinfiniselect returns a short-lived `content://` link backed directly by the
file descriptor. It does not copy the file. The file URI remains authoritative
and can be resolved again later while the caller still has filesystem access.

Use `InfiniSelect.resolveRelativeLocation(context, playlistUri, entry)` for a
relative playlist entry. `resolveRelativeLocationText` provides the same
normalization for string-based URI wrappers. The library resolves ordinary
hierarchical and SAF locations itself and delegates archive traversal to InfiniFolder. Playlist
writers should store the resulting authoritative URI as each track's
`location`, then call `resolveSource` only when a track is opened. InfiniSelect's
JSPF serializer follows this rule and omits temporary content relays from
directory and cover identity fields.

`InfiniSelect.defaultDirectory(context, clientId)` and
`InfiniSelect.setDefaultDirectory(context, clientId, uri)` store starting
folders in the consuming app's private preferences, partitioned by viewer ID.
The fallback screen also lets a user choose that viewer's default folder. A
successful selection updates the viewer's starting folder to the directory it
came from; dismissing the InfiniFolder picker after navigating saves the last
directory reached. Saving uses the same per-viewer folder and updates it after
choosing a destination, so load and save resume in the most recently used
location across launches.

## Saving documents

Use `InfiniSelect.save(activity, SaveRequest(...), requestCode)` to let the user
choose a destination for a document the viewer has generated. Publish in-memory
bytes with `InfiniSelect.createDocument(...)`, or expose a caller-owned local
file with `InfiniSelect.createFileDocument(...)`; pass the resulting source URI
and size in the request. When a new-file selection supplied a directory, use
`SelectionResult.directoryAuthoritativeUri` as `SaveRequest.initialDirectory`.

When InfiniFolder is installed, it opens its floating browser and writes into
the selected storage location. Existing files are kept; a numbered filename is
selected if the requested name already exists. If InfiniFolder is unavailable or
cannot open its picker, the library falls back to Android's
`ACTION_CREATE_DOCUMENT` flow, starting at the selected SAF directory when the
provider supports it. A successful result includes
`FileSelectionContract.EXTRA_SAVE_SUCCESS` and the actual saved name.

`FileSourceBackend` is the extension point for additional loading systems. It
provides an availability check, a visible label/order, and an Intent factory.
Backends can be registered with `FileSourceRegistry` or through Java
`ServiceLoader`; unavailable backends are omitted. Viewer apps keep the result
contract (`Activity` result with `data`, MIME type, display name, and URI read
grant) and choose their own rendering path.

Viewers can pass `supportedExtensions` alongside `mimeTypes` and set
`hideUnsupported = true` to hide unsupported files in listing-capable sources.
The InfiniFolder floating picker keeps directories and browsable archive or
filesystem-image containers visible, so users can navigate into them, while
only allowing matching files to be selected. MIME and extension matches are
combined, which lets a viewer accept files whose storage backend reports a
generic MIME type. The Android SAF fallback receives the MIME filters; Android
document providers do not expose a portable extension-filter API.

InfiniFolder streams selected items through a grantable, read-only content URI.
This supports files in VFS backends and archive entries without asking the
receiving app to reuse a remote credential. Seekable backends expose positional
access through an Android proxy descriptor; stream-only backends expose a pipe.
Clients inspect descriptor capabilities and need not copy a resource merely
because it crosses the application boundary. Relay references expire after 24 hours.

## Multiple items and playlists

Multiple selection keeps a grantable content URI and a separate authoritative
location for every item. A client can request selections of two or more items
as JSPF; each track's `location` is authoritative, while selection extras and
`ClipData` contain the current access URIs:

```kotlin
InfiniSelect.open(activity, OpenRequest(
    clientId = "infinimedia",
    displayName = "InfiniMedia",
    mimeTypes = listOf("audio/*", "video/*", "image/*", "application/xspf+xml", "application/jspf+json"),
    allowMultiple = true,
    multipleSelectionDelivery = MultipleSelectionDelivery.JSPF,
    metadataFormat = SelectionMetadataFormat.XML,
    allowRecursiveAudioDirectories = true,
), REQUEST_OPEN)
```

For one selected item, result `Intent.data` is its current content URI and
`EXTRA_RESULT_AUTHORITATIVE_URI` is its identity. For several items,
`Intent.data` and `EXTRA_RESULT_PLAYLIST_URI` identify the generated JSPF
document; parallel content and authoritative arrays retain each item. The
optional metadata XML has separate content and authoritative attributes.
Parse results with `InfiniSelect.readResult(context, data)`. Generated JSPF
and XML documents are served from the consuming app's cache through a
read-only provider and expire after 24 hours.

`allowRecursiveAudioDirectories` retains its legacy field name. When enabled, a
long press on a directory in InfiniFolder recursively enumerates files matching
the request's MIME and extension filters through its storage provider. InfiniMedia
requests audio, video and image formats, so this flow queues all supported media
through the same JSPF result. Symlink directories are not followed; unreadable
folders and scan limits are reported. An optional `OpenRequest.progressReceiver`
receives `RESULT_LOADING_STARTED`, `RESULT_LOADING_PROGRESS`, and
`RESULT_LOADING_FAILED` while the selector closes and enumeration continues. Each
result includes a human-readable progress label and folder/item counts. Selected items also include their
parent directory content URI and display name in `SelectionItem`, the parallel
`EXTRA_ITEM_DIRECTORY_URIS` / `EXTRA_ITEM_DIRECTORY_NAMES` arrays, and the
generated JSPF InfiniSelect extension fields. `directoryReference` pairs the
directory content URI with its authoritative location. This lets clients retain
directory grouping and artwork context without rescanning the hierarchy.
For recursive selections, InfiniFolder also attaches a read-granted URI to the
first readable `cover.*` image in each directory. It passes the URI through
`SelectionItem.directoryCoverUri` and the JSPF `directoryCoverUri` extension,
along with `directoryCoverReference` and `directoryArtworkMetadataResolved`.
The playlist stores URI references only, never image bytes. Viewers can show a
folder icon immediately and load the image during their background metadata
pass; directories without a cover keep the folder icon without repeating the
lookup for every track.

## Build

Install JDK 17 or 21, Python 3 and Android SDK platform 36/build tools 35.0.0.
Set `ANDROID_HOME` or this checkout's `local.properties`, then run:

```sh
python3 scripts/fetch-dependencies.py
./gradlew :select-android:assembleDebug :select-android:testDebugUnitTest
```

There are no InfiniSuite dependencies. No parent or sibling checkout is read.

Original code is licensed under MIT; see [LICENSE.md](LICENSE.md).

## Android storage contract

`StorageAccess.selectDirectory` requests a standard `ACTION_OPEN_DOCUMENT_TREE`
result, preferring Infinifolder's DocumentsProvider when available. `OpenRequest`
with `writable = true` requests a standard editable document. Read-only requests
retain the floating picker for compatibility. Both routes work with Android SAF
when Infinifolder is absent or unavailable. The provider authority is
`com.infinifolder.documents`; document IDs are opaque and must not be decoded.

Use `StorageAccess.openRead`, `openWrite`, or `openDescriptor`. The caller owns
and closes every handle. `seekable(descriptor)` probes the handle without copying
its contents; unsupported seeking remains explicit. `capabilities` returns
unknown where Android metadata cannot promise a feature. An advertised capability
does not replace the access check performed by the actual open operation.

`StorageAccess.persist(context, result)` persists only flags Android actually
granted. Temporary floating-picker relay URIs expire after 24 hours and cannot
be persisted. SAF document/tree references last until revoked by the user or
provider; network credentials remain private to Infinifolder. Neither route
materializes a complete file merely to provide a URI. A decoder that requires a
local file must explicitly choose and own its cache/staging policy.

Android DocumentsContract/ContentResolver APIs support tree traversal, child
creation and other provider operations. Unsupported operations fail explicitly.
The compatibility JSPF/XML result serializers encode selection references only;
media decoding and metadata extraction are outside this library.

Saved-document results also use Android content references and preserve the actual
read/write/persist flags. Infinifolder's save adapter verifies the framework caller
and its source access before reading. On Android 8–14, publish generated content
from a provider owned by the caller or pass a document with an explicit URI grant;
provider-wide access alone is insufficient for a cross-application permission
check. Newer Android versions additionally expose a full content permission check.
Capability metadata distinguishes an already persisted grant (`persisted`) from
seek support, which is probed on the actual open descriptor.
