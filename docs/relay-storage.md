# Relayed directory listings and shortcut collections

Version 0.1.2 adds worker-thread requests through InfiniFolder's existing public `com.infinifolder.selection` provider. Client applications need no shared-storage/all-files permission or Folder tree grant for these requests. InfiniFolder performs backend operations with its own access. Selected files still receive ordinary readable content relays through `InfiniSelect.requireSource`; listing a URI does not itself grant file access. InfiniFolder remains optional: Android's file picker and already granted SAF sources are supported without it.

```kotlin
val listing = FolderStorage.list(context, directory = null) // Virtual root
for (entry in listing.entries) {
    // entry.uri is authoritative; resolve it only when selecting/reading the file.
}
val collections = ShortcutCollections(context, "infiniplay")
val recents = collections.directory("recents", "nes")
collections.put("recents", "nes", FileShortcut(sourceIdentity, "game.nes", system="NES"), touch=true)
```

`FolderStorage.list` combines paged JSON responses into typed entries. The View refreshes loading directories; callers of the lower-level API can use `loading` and `notice`. At most 20,000 unique entries are accepted by this initial client. Existing local/remote/archive backends remain Folder-owned; no credentials or native backend classes enter the client.

## Listing request

`ContentResolver.call` method `io.github.gretchycat.infiniselect.LIST_DIRECTORY` accepts `directory` (authoritative URI string, absent for Virtual root) and `offset` (nonnegative integer). Response Bundle fields are `version=1`, `entries` (JSON array), `next` (next offset, or -1), `loading` and optional `notice`. Each record has:

```json
{"uri":"file:///storage/emulated/0/Games/game.gen","name":"game.gen","label":"game.gen","mime":"application/octet-stream","directory":false,"size":524288,"modified":123456789}
```

`size=-1` means unknown. `modified` is milliseconds since epoch, or 0. Directory records use the directory MIME type. Browsable archives are exposed as directories. Pages have at most 256 records and fit within a 384 KiB UTF-8 envelope; malformed, oversized or non-advancing pages fail explicitly. Stable URI ordering supports paging; listings are not immutable snapshots across external file changes. Access relays are requested lazily for the selected file, avoiding a read grant for every listed entry.

## Collection request

Method `io.github.gretchycat.infiniselect.SHORTCUT_COLLECTION` accepts `client`, safe `segments`, `operation` (`directory`, `put`, `remove`), optional version-1 `shortcut` JSON and `touch`. It returns the authoritative `uri` of the directory/link. InfiniFolder derives the scope using the verified Android caller package, not a claimed package extra. For `com.infiniplay` / `infiniplay`, the shared root is `Internal storage/infini/play`. Other callers receive their own package/client namespace; they cannot claim Play's namespace. Traversal and symlink directory escapes are rejected. Only same-target .iflink documents are removed; targets are never modified/deleted.

When Folder is absent, `ShortcutCollections` uses app-private link directories through the same API. It does not request broad storage access or pretend those links are in shared storage. Installing Folder switches to the shared relay namespace; hosts may seed existing identities there. Old installed Folder builds must be updated to support these requests; unsupported methods do not fall back to privileged client-side access.

JSON round-trip, page bounds, caller namespaces, traversal/symlink rejection and target-preserving removal have JVM checks. Cross-app Binder grants, backend loading, storage denial in Folder and UI acceptance require device testing.

[Deferred playlist ownership migration](TODO.md): playlist generation will move to InfiniMedia using this listing API later.
