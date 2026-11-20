# Embedded browser and file links

Version 0.1.1 adds `EmbeddedFileBrowser(context)`, a reusable Android View for a consuming app's layout. There is no dependency on InfiniFolder application source. It lists directories asynchronously and delivers UI-thread callbacks. The host owns permissions, activity results and file actions.

```kotlin
val browser = EmbeddedFileBrowser(activity).apply {
    onSelected = { item -> openDocument(item) }
    fileActions = { item -> listOf(SelectionAction("add_favorite", "Add to favorites")) }
    onFileAction = { actionId, item -> handleAppAction(actionId, item) }
    onRequestTree = { initial -> StorageAccess.selectDirectory(activity, 42, initial) }
    onRequestLocalAccess = { requestStoragePermission() }
    onRequestOpen = { openNormalInfiniSelectPicker() }
    onError = { message -> showError(message) }
}
container.addView(browser)
browser.open() // Virtual root
// Or browser.open(Uri.fromFile(directory), "Recents")
```

Virtual root lists internal shared storage, persisted SAF trees and optional InfiniFolder sources. First use of Folder sources asks for an Android tree grant at `com.infinifolder.documents` / `root`. After the host persists the actually granted result with `StorageAccess.persist` and reopens the browser, Folder's local/remote/container sources appear inline. Archive navigation follows its provider's directory representation. No credentials, backend classes or private files are copied from Folder. Without Folder, local files, granted SAF trees and Android file selection remain available.

Tree browsing requires an actual tree grant. Hosts may supply `canBrowseLocal` for their Android shared-storage access policy. Local selections pair temporary `createFileDocument` access with durable file identity; provider selections pair their tree-granted access URI with canonical identity. Errors remain visible. Detaching the View stops its worker and content observer. Resource-provider notifications refresh listings. This component uses standard file/folder symbols, not Folder's full theme/thumbnail or file-operation UI.

The breadcrumb bar contains Back, path and a persistent list/icon toggle. `breadcrumbsEnabled` hides/shows the entire bar; `iconMode` changes layout without changing location. Both preferences are private to the consumer. `goBack()` reports whether a parent was opened. `open(directory, label)` sets a navigation boundary.

`FileShortcut` reads/writes compatible version-1 `.iflink` JSON with `targetUri`, `displayNameOverride`, `targetMimeType`, `targetIsDirectory=false`, `createdAtMillis` and optional `infiniPlaySystem`. It rejects temporary Folder targets, directory links, unknown versions and documents larger than 128 KiB. `writeTo(directory)` atomically creates a link with a stable SHA-256 name or reuses an existing same-identity link, including user-named links. Removing a link never removes the target.

Standalone AAR/JVM checks cover interoperability, malformed/oversized links, bounded reads, repeated writes and separate recent/favorite links. Touch UI, URI grants, provider notifications and accessibility require device acceptance.

## Caller-owned long-press actions

`fileActions: (SelectionItem) -> List<SelectionAction>` supplies labels/IDs dynamically for embedded file menus. The browser displays Open plus those actions; `onFileAction(id, item)` returns control to the caller. Favorites, metadata lookup and artwork services belong to the caller, not this library. The older `onFileMenu` whole-menu callback remains available when the action list is empty.

For a floating single-file selector, set `OpenRequest.fileActions`. Updated InfiniFolder displays those labels on file long press and returns the usual grant-aware selection plus `SelectionResult.actionId`. Handle the action instead of opening the file. IDs/labels travel as bounded contract arrays; only a requested ID is forwarded. Selecting an action closes the picker. Existing folder New/multi-selection actions are preserved; custom file actions apply to single-file read selection. Android's system picker and older Folder builds do not expose this menu and return normal selections. No arbitrary callback code or game-specific operation runs inside the provider.
