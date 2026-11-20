# Embedded browser and file links

`EmbeddedFileBrowser(context, preferredViewMode = FileViewMode.LIST)` is a reusable Android View. The optional preference accepts LIST or ICON; null uses the saved mode. It initializes this instance without preventing the user from toggling. `OpenRequest(preferredViewMode = FileViewMode.ICON, ...)` passes the same preference to the floating selector. Unknown wire values are ignored. User toggles persist the mode for requests without an override.

```kotlin
val browser = EmbeddedFileBrowser(activity, FileViewMode.LIST).apply {
    onSelected = { item -> openDocument(item) }
    fileActions = { listOf(SelectionAction("add_favorite", "Add to favorites")) }
    onFileAction = { id, item -> handleAppAction(id, item) }
    onRequestTree = { initial -> StorageAccess.selectDirectory(activity, 42, initial) }
    onRequestOpen = { openNormalInfiniSelectPicker() }
    onError = { message -> showError(message) }
}
container.addView(browser)
browser.open() // Virtual root; or open(authoritativeDirectory, label)
```

The browser requests [paged JSON directory listings](relay-storage.md) from InfiniFolder and lazily obtains a read relay for selected files. Folder owns local/remote/archive access; clients need no broad storage permission or Folder tree grant. No application implementation classes or credentials are copied. Without Folder, granted SAF directories, Android file selection and app-owned private link directories remain usable. The host owns ordinary Android picker results/grants and application-specific actions. Basic file/folder symbols are used; this is not Folder's full thumbnail/theme or file-operation UI.

The breadcrumb bar contains Back, path and the list/icon toggle. `breadcrumbsEnabled` hides/shows the whole bar; `iconMode` switches layout at runtime. Both user preferences persist per consumer. `goBack()` reports whether a parent was opened; `open(directory, label)` sets a navigation boundary. Listings run on a worker, callbacks run on the UI thread, loading backends are refreshed and detach stops the worker/observer.

## File links

`FileShortcut` reads/writes version-1 `.iflink` JSON with `targetUri`, `displayNameOverride`, `targetMimeType`, `targetIsDirectory=false`, `createdAtMillis`, plus optional `infiniPlaySystem`. Documents are bounded to 128 KiB. Temporary Folder targets, directory links and unknown versions are rejected. `writeTo(directory)` atomically creates a SHA-256-named file or reuses an existing same-identity link, including user-named links. Client apps should use `ShortcutCollections` for shared directories; only the storage-owning process writes those files. Removal never deletes the target.

## Caller-owned long-press actions

`fileActions: (SelectionItem) -> List<SelectionAction>` supplies embedded menu labels/IDs. The View displays Open and those actions; `onFileAction(id, item)` returns control. Favorites, metadata/artwork services and other side effects belong to the caller. The older whole-menu `onFileMenu` callback remains available when no actions are supplied.

For floating single-file read selection, set `OpenRequest.fileActions`. Updated Folder returns normal file access/identity and `SelectionResult.actionId` after a long-press action. Handle the action instead of opening the file. Only requested action IDs are forwarded. Actions close the picker; folder New and multi-selection behavior is preserved. Android's picker and older Folder builds return ordinary selections without this menu.
