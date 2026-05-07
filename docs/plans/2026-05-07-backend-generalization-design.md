# Backend generalization: TrackedTree + CloudDoc

**Date:** 2026-05-07
**Branch:** `feat/backend-generalization`
**Status:** draft, awaiting approval

## Problem

Sketchbook today syncs exactly one thing: `.als` projects. The user has two machines — fresh Mac, Windows full of plugins/presets/templates — and three goals:

1. **One-step new-machine setup**: install Sketchbook, sign in, pull, install plugins, ready.
2. **Continuous sync of music-domain artifacts** beyond projects (User Library, machine state).
3. **Backend that admits future use cases** — stems, renders, multi-user collab (already on the roadmap per `UserId` docstring), shared sample folders, M4L libraries — without a parallel sync stack per artifact type.

The current code shape almost generalizes but doesn't. Every cloud surface keys on `ProjectUuid`:

- `CloudBackend.{headBlob, putBlob, readManifest, listManifests, appendManifestHead, acquireLock, …}` — all `(uuid: ProjectUuid, …)`.
- `Manifest.projectUuid` is on the wire format (`v=1`).
- `BlobScope.Private(uuid: ProjectUuid)` — already the precedent for namespacing the same blob store across cohorts; just hard-coded to projects.
- `LeaseLock` is documented as `locks/<project_uuid>.lock`; carries `host_id` but not `user_id`.
- `sync_state` table PKs on `project_uuid`.
- No discovery surface — Machine B can't ask the cloud "what does this user own?"
- Bucket layout encodes ownership in the URL prefix (`<user>/...`), which makes cross-user sharing a wire change later.

Conversely, `WorkingTree` is *fully* generic — `list / stat / read / hash`, no project assumptions. The pipeline machinery (CAS HEAD, dedup HEAD-then-PUT, branch-on-conflict) is all reusable; the identity layer is what's pinned.

## Approach

### Two cloud primitives, not one

Add a clean line between two storage shapes the system needs:

- **`TrackedTree`** — a content-addressed, history-bearing file collection. Manifest + blobs + snapshots + lease lock + conflict policy. The thing we already have for projects, generalized over identity. Use cases: `Project`, `UserLibrary`, future `Stems`, future `SamplePack`.
- **`CloudDoc`** — a small structured JSON object with CAS but no history, no manifest, no blob layer. Use cases: tree registry, per-host machine slices, account settings, future invitations / sharing tokens.

Forcing small structured config through tree machinery is over-engineering and exposes accidental complexity (in-memory working trees, manifests of one file). Two primitives keep each shape clean.

```
                  ┌─────────────┐
                  │ CloudBackend│
                  └──────┬──────┘
            ┌────────────┴────────────┐
            ▼                         ▼
     ┌──────────────┐          ┌────────────┐
     │ TrackedTree  │          │  CloudDoc  │
     │  (history,   │          │   (CAS,    │
     │   manifests, │          │    flat,   │
     │   blobs)     │          │    small)  │
     └──────────────┘          └────────────┘
```

### Identity: opaque, registered, never derived

`TrackedTreeId` is an **opaque ULID-shaped string**. It is *not* derived from `ProjectUuid` or `UserId`. A `TreeRegistry` CloudDoc maps `(owner_user_id, kind, scope_key) → tree_id`.

```kotlin
@JvmInline value class TrackedTreeId(val value: String)   // ULID, alphanumeric+dash+underscore

sealed interface TrackedTreeKind {
    data object Project       : TrackedTreeKind
    data object UserLibrary   : TrackedTreeKind
    // Reserved future kinds: Stems, Renders, SamplePack, MaxForLive, …
}
```

Why opaque IDs:

- **Decouples tree identity from owning resource.** A project might one day have multiple trees (Project + Stems). A user might have multiple UserLibraries. A tree might transfer ownership across users in a future collab feature. Encoded ids ("project:<uuid>") rule those futures out.
- **Charset compatibility.** `core/Ids.kt` restricts safe-id chars to alphanumeric+`-`+`_`. ULIDs satisfy that; encoded ids with `:` separators don't.
- **Renames are free.** Tree's `display_name` lives in the registry; changing it doesn't touch any object key.
- **Registry is the discovery surface** — Machine B asks the registry once, gets the full tree list with metadata.

### TreeRegistry shape (v1, multi-user-ready)

```json
{
  "v": 1,
  "owner_user_id": "11223344",
  "trees": [
    {
      "tree_id": "tt-01HZ3W…",
      "kind": "project",
      "scope_key": "<project_uuid>",
      "display_name": "lofi-sketch-april",
      "owner_user_id": "11223344",
      "collaborators": [],
      "created_at": "2026-05-07T...",
      "created_by_host": "macstudio-jared"
    },
    {
      "tree_id": "tt-01HZ3X…",
      "kind": "user_library",
      "scope_key": "default",
      "display_name": "Ableton User Library",
      "owner_user_id": "11223344",
      "collaborators": [],
      "created_at": "2026-05-07T...",
      "created_by_host": "macstudio-jared"
    }
  ]
}
```

Stored at `<user>/registry.json` as a `CloudDoc`. Updates are CAS via `Generation`. Race for adding a new tree: read → mutate → write-with-expected-generation; on conflict, re-read and retry.

`scope_key` is the kind-specific natural key — `ProjectUuid` for projects, a label for User Library — used to find "the tree for this project" without scanning. Not material for cloud keys.

**Multi-user readiness now (decision):**

The registry carries `owner_user_id` per entry plus a `collaborators: [{ user_id, role }]` array. v1 always sets `owner_user_id = UserId.DEFAULT` and `collaborators = []`; v1.2 multi-user populates these. The shape is committed now so:

- The wire format is stable across the v1 → v1.2 transition.
- Permission checks at the app layer (`canRead(treeId, userId)`, `canWrite(treeId, userId)`) work today (always true for the owner) and are the same check v1.2 invokes.
- Sharing UX in v1.2 is an additive feature, not a wire-format break.

```kotlin
@Serializable
data class TreeRegistryEntry(
    val treeId: TrackedTreeId,
    val kind: TrackedTreeKind,
    val scopeKey: String,
    val displayName: String,
    val ownerUserId: UserId,
    val collaborators: List<Collaborator> = emptyList(),
    val createdAt: Instant,
    val createdByHost: String,
)

@Serializable
data class Collaborator(val userId: UserId, val role: CollabRole)

@Serializable
enum class CollabRole { Read, Write, Admin }
```

Cloud-side enforcement of ACLs is bucket-level today (the SA has full bucket access; trust is at the app layer driven by registry). v1.2 may move to backend-mediated GCS access; the registry-as-source-of-truth design works for both.

### CloudBackend surface

```kotlin
interface CloudBackend {
    // ── Blobs (unchanged shape; scope widens conceptually but type stays) ──
    suspend fun headBlob(hash: BlobHash, scope: BlobScope = BlobScope.Shared): Boolean
    suspend fun putBlob(hash, source, size, scope = BlobScope.Shared)
    suspend fun getBlob(hash, scope = BlobScope.Shared): RawSource

    // ── Manifests (TrackedTree only) ──
    suspend fun readManifest(treeId: TrackedTreeId, kind: TrackedTreeKind, rev: SnapshotRev): Manifest
    suspend fun listManifests(treeId, kind, sinceRev: SnapshotRev?): List<ManifestRef>
    suspend fun appendManifestHead(treeId, kind, expectedHead: Generation?, manifest: Manifest): Result<Generation>

    // ── Lease (TrackedTree, only when KindPolicy says required) ──
    suspend fun acquireLock(treeId, kind, lock: LeaseLock): LeaseAcquireResult
    suspend fun refreshLock(treeId, kind, lock, expected: Generation): LeaseRefreshResult
    suspend fun releaseLock(treeId, kind, expected: Generation)

    // ── CloudDoc (new) ──
    suspend fun readDoc(key: CloudDocKey): CloudDocRead?               // null = doesn't exist
    suspend fun writeDoc(key: CloudDocKey, expected: Generation?, bytes: ByteArray): Result<Generation>
    suspend fun listDocs(prefix: CloudDocKey.Prefix): List<CloudDocRef>
}

data class CloudDocRead(val bytes: ByteArray, val generation: Generation)

@JvmInline value class CloudDocKey(val path: String) {
    @JvmInline value class Prefix(val value: String)
}
```

`BlobScope.Private` stays `ProjectUuid` — see "Why not widen BlobScope.Private" below.

### KindPolicy

| Concern                        | Project                              | UserLibrary                              |
|--------------------------------|--------------------------------------|------------------------------------------|
| Lease lock                     | required, 15min TTL                  | none (CAS HEAD wins)                     |
| Conflict mode                  | branch-fork (current)                | merge (LWW per relpath, with tombstones) |
| ALS path patching              | yes                                  | no (flippable — see Templates below)     |
| Contributes to plugin manifest | yes (`project_plugins`)              | yes (`user_library_plugins`)             |
| Skip-set                       | (existing JvmWorkingTree set)        | see "UserLibrary skip-set" below         |
| Default BlobScope              | Shared (Private if `self_contained`) | Shared                                   |

```kotlin
data class KindPolicy(
    val leaseRequired: Boolean,
    val conflictMode: ConflictMode,
    val privateScopeAllowed: Boolean,
    val alsPatchingEnabled: Boolean,            // run AlsPatcher post-materialize on .als files
    val contributesToPluginManifest: Boolean,   // walk .als for plugin refs into bootstrap union
)

sealed interface ConflictMode {
    data object BranchFork : ConflictMode
    data class Merge(val deletePolicy: DeletePolicy) : ConflictMode
}

sealed interface DeletePolicy {
    data object Tombstones    : DeletePolicy
    data object IgnoreDeletes : DeletePolicy   // additive-only kinds (none today)
}
```

Pipeline reads `KindPolicy` and gates lease + conflict-resolution on it. **No `if (kind == Project)` branches in the pipeline.**

### UserLibrary skip-set (decided upfront)

Live writes content + auto-generated metadata + OS junk into the User Library. Sketchbook syncs the *content*, skips the rest.

```kotlin
internal object UserLibrarySkipSet {
    // Directories whose entire subtree is excluded (matched by exact name on any path component).
    val SKIP_DIRS: Set<String> = setOf(
        "Ableton Project Info",   // Live's auto-generated UL metadata; same as in projects
        "Cache",                  // historical — Live ≤10; defensive for users who upgraded
        ".fseventsd",             // macOS Spotlight indexer
        ".Spotlight-V100",        // macOS Spotlight indexer
        ".Trashes",               // macOS trash on external volumes
        "\$RECYCLE.BIN",          // Windows recycle bin if user library on a separate drive
    )

    // File-name patterns excluded everywhere.
    val SKIP_FILE_NAMES: Set<String> = setOf(".DS_Store", "Thumbs.db", "desktop.ini")
    val SKIP_FILE_PREFIXES: List<String> = listOf(".", "~$")  // dotfiles + Office-style locks
    val SKIP_FILE_SUFFIXES: List<String> = listOf(
        ".als.bak",   // Live autosave
        ".tmp",
    )
}
```

What's *kept* (the user-content surface — none of these is excluded):

- `*.adg` (Audio/Instrument Effect Rack), `*.adv` (preset)
- `*.alc` (Live clip), `*.als` (Live set / template)
- `*.amxd` (Max for Live device)
- `*.adp` (drum kit), `*.agr` (groove)
- Audio sample files (`.wav`, `.aif`, `.aiff`, `.flac`, `.mp3`, `.m4a`, `.ogg`)

The skip-set is conservative — under-skipping just sends a few KB of cache files we'd rather not have, never breaks sync. Over-skipping silently drops user content, which is much worse. Bias toward keeping.

This skip-set will be confirmed via a one-launch spike (fresh Live install, fresh save, observe filesystem diff) before commit 11 lands. The spike is cheap; no design rework expected.

### Manifest delete semantics (tombstones)

Without tombstones: A deletes `foo.adv`, B is unchanged → merge re-adds `foo.adv`. Correctness bug.

Fix: tombstones in `Manifest.files`. A `ManifestFile` gains a `deleted: Boolean` (default false). When the working-tree producer observes a relpath was present in the parent manifest but is absent on disk, it emits a tombstone instead of dropping it. Tombstones survive merges (LWW by mtime). Materializer treats tombstones as "delete the file if present."

```kotlin
@Serializable
data class ManifestFile(
    @SerialName("hash") val hash: BlobHash? = null,         // null when deleted=true
    @SerialName("size") val size: Long = 0,
    @SerialName("mtime") val mtime: Instant,
    @SerialName("deleted") val deleted: Boolean = false,
)
```

Tombstone bloat is bounded by the active relpath set's churn; for a typical User Library that's tens of files added/removed per year. Acceptable. A future LCA-walk 3-way merge would let us drop tombstones eventually — out of scope here.

### ManifestMerger

Pure function, used only when `ConflictMode.Merge`:

```kotlin
fun mergeManifests(local: Manifest, remote: Manifest, hostId: String, clock: Clock): Manifest
```

Strategy:
- File set = union of relpaths across both manifests (including tombstones).
- Per relpath conflict: pick the entry with later `mtime`; tie-break by `hostId` lexicographic.
- New `rev = max(local.rev, remote.rev) + 1`; `parentRev = remote.rev` (the one that beat us in CAS); `snapshotKind = SnapshotKind.Auto`.
- Recompute `stats` from the merged file map (tombstones excluded from `total_bytes`/`file_count`).

`SnapshotPipeline` on CAS conflict in Merge mode: fetch the winning HEAD's manifest, merge, re-CAS at the next rev. Up to 3 retries before bailing as `Failed` — protects against hot-spinning if both machines are saving constantly.

### Wire format: `Manifest`

`v=1` → `v=2`:

```kotlin
@Serializable
data class Manifest(
    @SerialName("v") val version: Int = 2,
    @SerialName("owner_user_id") val ownerUserId: UserId,
    @SerialName("tree_id") val treeId: TrackedTreeId,             // was project_uuid
    @SerialName("tree_kind") val kind: TrackedTreeKind,           // new
    @SerialName("rev") val rev: SnapshotRev,
    @SerialName("parent_rev") val parentRev: SnapshotRev? = null,
    @SerialName("timestamp") val timestamp: Instant,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("snapshot_kind") val snapshotKind: SnapshotKind,  // renamed: was `kind`
    @SerialName("label") val label: String? = null,
    @SerialName("self_contained") val selfContained: Boolean = false,
    @SerialName("files") val files: Map<String, ManifestFile>,    // now may include tombstones
    @SerialName("stats") val stats: ManifestStats,
)
```

`v=1` reads happen via a back-compat decoder in `cloud-impl`. Decoder lifetime: from "v=2 writes ship" through "all known machines have run the migration" — see "Migration UX" below.

### LeaseLock carries userId

Forward-compat for multi-user:

```kotlin
@Serializable
data class LeaseLock(
    val ownerUserId: UserId,
    val ownerHostId: String,
    val ownerHostName: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val heartbeatSeq: Long = 0,
)
```

v1 always sets `ownerUserId = UserId.DEFAULT`; v1.2 fills in real ids. Already serializing it now means no wire change later.

### Why not widen `BlobScope.Private`

"Private blob pool" is a project-only concept — `sync_state.self_contained` exists specifically so a deliverable project's bytes never dedup with anything else. Keeping `Private(uuid: ProjectUuid)` preserves the constraint at the type level — no other kind can accidentally request a private pool. The `KindPolicy.privateScopeAllowed` flag is informational; the type is the real enforcer.

### GCS layout

```
<user>/
  registry.json                                    # CloudDoc — list of all trees + ACL
  trees/
    <kind>/<tree_id>/
      lock                                         # if KindPolicy.leaseRequired
      manifests/
        <rev:08d>-<timestamp>-<host>.json
  blobs/
    <aa>/<hash>                                    # shared dedup pool
  blobs-private/
    <project_uuid>/<aa>/<hash>                     # project-only (self_contained=1)
  profile/
    plugin_manifest_<host_id>.json                 # CloudDoc — host-sliced
    machines.json                                  # CloudDoc — host roster (read-merge-CAS)
    ableton_versions.json                          # CloudDoc — per-project version map
```

Bucket sharding strategy for scale: this layout works as a single bucket with strict per-user prefix to ~10⁴ users. Beyond that or for data-residency reasons we'd shard per-tenant; the cloud-key shape is unchanged (just `gs://<bucket-for-tenant>/<user>/...`). No design impact here; documented for posterity.

### Migration UX (decided upfront — mandatory on first launch)

Sketchbook detects legacy paths (`<user>/manifests/<uuid>/...`) on startup and **must** migrate before any sync work proceeds. No skip option — bounding the v=1 decoder's lifetime to the deploy fan-out window (days) is worth the small UX cost of a forced one-time dialog.

Dialog flow:

```
┌─────────────────────────────────────────────────────────┐
│  Cloud storage upgrade                                  │
│                                                         │
│  Sketchbook needs to reorganize your cloud storage      │
│  to support new sync features (User Library, machine    │
│  profile). This runs once per machine and is safe to    │
│  retry — your local files are untouched.                │
│                                                         │
│  On this machine:                                       │
│   • 23 project trees                                    │
│   • 412 manifest files to relocate                      │
│   • 0 blob files to move (content stays put)            │
│                                                         │
│  [Preview…]  [Run migration]                            │
└─────────────────────────────────────────────────────────┘
```

- **Preview** opens a dry-run report showing exact source → destination paths.
- **Run migration** is the only forward action. Closing the dialog without running quits the app — there's no degraded mode to fall back into.

On completion, the migrator writes `migration_complete = true` to local `Settings`, registers this host into `machines.json`, and the app proceeds to the normal startup flow.

The migrator is idempotent: running it on a partially-migrated bucket completes the rest. It is *also* the entry point for first-time registry creation — it builds `registry.json` from `project_identity` rows on the local catalog and registers a UserLibrary tree with `scope_key = "default"`.

**v=1 decoder lifetime.** The decoder lives in `DirectGcsBackend` to handle the brief overlap when one machine has the v=2 binary (and migrated) while another still runs v=1. Concretely: Mac upgrades, runs migration, writes v=2; Windows still on old binary keeps writing v=1 paths until *its* upgrade. The decoder lets the v=2 Mac read the v=1 stragglers in the meantime. A small banner — "Windows is still on the old version — open Sketchbook there to finish the upgrade" — appears whenever `machines.json` shows a host on a pre-v=2 binary version. Once every roster entry reports v=2, a follow-up PR drops the decoder.

### Catalog DB

Today's `sync_state` and `snapshots` tables PK on `project_uuid`. Non-project trees get parallel tables (rejected: widening project tables, because every join to `project_identity` / `journal_entries` / `repair_acks` etc. would need a kind filter or break).

Schema (`10.sqm`):

```sql
-- Sync state for non-project trees (UserLibrary, future kinds).
CREATE TABLE tree_sync_state (
    tree_id        TEXT NOT NULL,
    tree_kind      TEXT NOT NULL,
    local_rev      INTEGER NOT NULL DEFAULT 0,
    cloud_head_rev INTEGER,
    dirty          INTEGER NOT NULL DEFAULT 0,
    updated_at     INTEGER NOT NULL,
    PRIMARY KEY (tree_id)
);

-- Local mirror of cloud registry — fast queries without hitting GCS.
CREATE TABLE tree_registry_cache (
    tree_id          TEXT PRIMARY KEY,
    tree_kind        TEXT NOT NULL,
    scope_key        TEXT,
    display_name     TEXT,
    owner_user_id    TEXT NOT NULL,
    updated_at       INTEGER NOT NULL
);
CREATE INDEX tree_registry_by_kind ON tree_registry_cache(tree_kind, scope_key);

-- Snapshot history for non-project trees (parallel to `snapshots`).
-- Decided upfront: cheaper to add now than to migrate later when UI lands.
CREATE TABLE tree_snapshots (
    tree_id          TEXT NOT NULL,
    rev              INTEGER NOT NULL,
    parent_rev       INTEGER,
    timestamp        INTEGER NOT NULL,
    host_id          TEXT NOT NULL,
    snapshot_kind    TEXT NOT NULL,
    label            TEXT,
    file_count       INTEGER NOT NULL,
    total_bytes      INTEGER NOT NULL,
    new_bytes        INTEGER NOT NULL,
    manifest_path    TEXT NOT NULL,
    PRIMARY KEY (tree_id, rev)
);
CREATE INDEX tree_snapshots_by_kind ON tree_snapshots(tree_id, snapshot_kind, rev DESC);

-- Sync events for non-project trees (merges, conflicts, materializations).
-- Drives a future "Sync activity" view + debugging today.
CREATE TABLE tree_journal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tree_id         TEXT NOT NULL,
    tree_kind       TEXT NOT NULL,
    timestamp       INTEGER NOT NULL,
    host_id         TEXT NOT NULL,
    event_kind      TEXT NOT NULL,    -- 'snapshot' | 'merge' | 'materialize' | 'conflict'
    payload_json    TEXT NOT NULL,
    rev             INTEGER
);
CREATE INDEX tree_journal_by_tree ON tree_journal(tree_id, timestamp DESC);

-- Plugin references discovered in materialized User Library trees.
-- Parallel to `project_plugins`. Drives the bootstrap checklist's coverage of templates +
-- default racks + Defaults/*.als — anything that lives in the User Library and references
-- plugins by name. Populated by UserLibraryPluginScanner after each materialize event.
CREATE TABLE user_library_plugins (
    tree_id        TEXT NOT NULL,
    rel_path       TEXT NOT NULL,        -- the .als / .adg file inside the UL tree
    plugin_name    TEXT NOT NULL,
    plugin_type    TEXT NOT NULL,        -- 'vst3' | 'vst' | 'au' | 'component'
    is_installed   INTEGER NOT NULL DEFAULT 0,
    last_seen_at   INTEGER NOT NULL,
    PRIMARY KEY (tree_id, rel_path, plugin_name, plugin_type)
);
CREATE INDEX user_library_plugins_by_name ON user_library_plugins(plugin_name, plugin_type);
```

`tree_journal` is observability infrastructure for sync events on non-project trees, parallel to `journal_entries` for projects. `PullPoller` and `SnapshotPipeline` write entries on every snapshot/merge/materialize so the user (and we) can answer "why did my User Library suddenly look different?"

## Tree types — concrete shape

### Project (existing, retargeted)

- Identity: registry-minted `tree_id`; scope_key = `ProjectUuid.value`.
- Working tree: existing `JvmWorkingTree`, gains tombstone emission for deleted relpaths.
- Policy: `BranchFork`, `leaseRequired = true`, `privateScopeAllowed = true`.

### UserLibrary

- Root: Mac `~/Music/Ableton/User Library/`, Windows `%USERPROFILE%\Documents\Ableton\User Library\`. Path resolved per machine via `SettingsRepository.userLibraryRoot` with OS-default fallback.
- Working tree: new `UserLibraryWorkingTree` using the skip-set above.
- Snapshot trigger: filesystem watcher on root (existing `Watcher` infra), debounce 60s (User Library writes are bursty when Live boots — longer than the project debounce).
- Policy: `Merge(Tombstones)`, `leaseRequired = false`, `privateScopeAllowed = false`.
- Registered during the migration tool's first run; `scope_key = "default"`.

### MachineProfile (CloudDocs, host-sliced)

Not a TrackedTree. Three CloudDocs:

```
<user>/profile/plugin_manifest_<host_id>.json       # this host's contribution
<user>/profile/machines.json                         # roster: hostId → name, lastSeen, OS
<user>/profile/ableton_versions.json                 # per-project: which Live version saved last
```

Per-host slicing for `plugin_manifest`: each host writes only its own file. Readers list-prefix `<user>/profile/plugin_manifest_*.json` and union. **No write conflicts.** `machines.json` and `ableton_versions.json` are merged-style CloudDocs (read → mutate → CAS write); writes are rare so retry-on-conflict is fine.

`plugin_manifest_<host_id>.json` content:

```json
{
  "v": 1,
  "host_id": "macstudio-jared",
  "host_name": "Jared's Mac Studio",
  "os": "darwin",
  "computed_at": "2026-05-07T...",
  "plugins": [
    { "name": "Serum", "vendor": "Xfer Records", "format": "vst3", "installed": true,
      "first_seen_in_project": "<project_uuid>", "last_seen_at": "..." }
  ]
}
```

Composition rule for the bootstrap UI: union by `(name, vendor, format)`; "installed somewhere" if any host reports `installed: true`; "needs install on this host" if installed elsewhere and not here.

## Templates: plugin discovery + path patching

Live's User Library holds project templates that are `.als` files just like projects:

- `User Library/Templates/*.als` — "New Live Set From Template" picks
- `User Library/Defaults/Default.als` — the default Live Set
- `User Library/Defaults/{Audio Effect Rack,Instrument Rack,…}.adg` — "Save as Default" rack files (parsed the same way as `.als` for plugin references)

Two cross-machine concerns apply to templates the same way they apply to projects, both addressed by `KindPolicy` flags rather than special-cased code:

### `contributesToPluginManifest` — plugin discovery

Templates that use plugins not used in any project must still appear in the bootstrap checklist. The fix is a focused scanner extension, not a special path:

- New table `user_library_plugins` parallel to `project_plugins` (catalog migration, same `10.sqm`).
- New `UserLibraryPluginScanner` (jvmMain) walks the materialized User Library for `*.als` and `*.adg` files post-pull, runs the existing `AlsParser`, and writes plugin references into `user_library_plugins`.
- `MachineProfileStore.composeHostSlice()` builds `plugin_manifest_<host_id>.json` from the union of `project_plugins` and `user_library_plugins`, so the bootstrap UI's "needs install" list covers both surfaces.

The scanner runs after `ManifestMaterializer.materialize` for any tree where `KindPolicy.contributesToPluginManifest = true`. Hooked off the `tree_journal` `materialize` event so it's policy-driven, not per-kind hard-coded — when a future `Stems` or `MaxForLive` kind appears, flipping the flag is the only change needed.

### `alsPatchingEnabled` — sample path patching

Live's browser system handles cross-platform paths internally for samples *inside* the User Library (resolved by browser-location ID, not absolute path), so most templates round-trip Mac↔Windows without intervention. The two edge cases:

- **Templates referencing samples outside the User Library.** Rare, but exists.
- **Non-default User Library root** on one of the machines (`SettingsRepository.userLibraryRoot` set differently per machine).

Default is `alsPatchingEnabled = false` for `UserLibrary` — we don't preemptively rewrite paths Live already handles. The flag is committed in code from day one so that if/when we observe a template with broken sample paths after a round-trip, flipping it on is a settings change, not a re-architecture. The Materializer reads the flag and skips the post-materialize patcher when false.

This keeps the design honest: templates *are* `.als` files and the cross-machine machinery applies, but the policy table — not a parallel implementation — is what governs which parts run for which kind.

## Bootstrap flow (goal #1) — data dependencies

The new-machine experience is the proof that the design holds together. Walked end-to-end:

1. User installs Sketchbook on Mac. App opens.
2. **Onboarding: sign in** (existing — OAuth from #116).
3. **Discover trees**: app reads `<user>/registry.json`. Mirrors entries into `tree_registry_cache`.
4. **Pull project trees**: for each project entry in the registry, run `PullPoller.subscribe(treeId, kind=Project)`. Materialization is on-demand (existing behavior — projects don't auto-pull until the user opens one).
5. **Pull UserLibrary tree**: `PullPoller.subscribe(treeId, kind=UserLibrary)` → `ManifestMaterializer.materialize(...)` writes the entire User Library to `~/Music/Ableton/User Library/`. (First materialization is the heavy one; subsequent runs are incremental.)
6. **Plugin checklist**: list-prefix `<user>/profile/plugin_manifest_*.json`, union by `(name, vendor, format)`, filter to "not installed on this OS." Each host's slice is itself the union of `project_plugins` and `user_library_plugins` from that host's catalog — so plugins that only show up in templates count too. Display as a checklist with vendor + format. UI design is a separate plan doc; **the data is in place**.
7. User installs plugins manually. App re-runs `JvmPluginPresenceProbe` after the user clicks "I installed these"; the new presence info gets written to *this host's* `plugin_manifest_<host_id>.json`.

The data dependencies pin down what this design has to deliver:
- Registry CloudDoc with per-tree metadata.
- TrackedTree pull that works for both Project and UserLibrary kinds.
- `UserLibraryPluginScanner` populates `user_library_plugins` post-materialize.
- CloudDoc list-prefix for the plugin manifest union.
- Per-host `plugin_manifest_<host_id>.json` written from the union of `project_plugins` + `user_library_plugins`, on probe completion.

All five are committed in the rollout below.

## Bootstrap UI (in scope, final commit)

The new-machine flow ends in a Compose screen that presents the plugin checklist and lets the user mark plugins as installed. Lives in `app-desktop`, hooked into the post-onboarding navigation graph.

### Trigger

Shown as the final step of first-launch setup, in this order:
1. Sign in (existing)
2. Mandatory migration (commit 10) — only if legacy paths detected
3. **Plugin checklist** — only if any plugin row reports "needs install on this OS" after the registry pull + UL materialize complete

If every plugin needed by any project or template is already installed locally, the screen is skipped — no point making the user dismiss an empty list.

### Wireframe

```
┌─────────────────────────────────────────────────────────────┐
│  Set up this Mac                                             │
│  Found 47 plugins across your projects and templates.        │
│  12 of them aren't installed on this Mac yet.                │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ ☐  Serum                          Xfer Records  vst3 │    │
│  │    Used in 23 projects, 4 templates                  │    │
│  │    [Find online ↗]                                   │    │
│  ├──────────────────────────────────────────────────────┤    │
│  │ ☐  FabFilter Pro-Q 3              FabFilter      au  │    │
│  │    Used in 18 projects                               │    │
│  │    [Find online ↗]                                   │    │
│  ├──────────────────────────────────────────────────────┤    │
│  │ …                                                    │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  [Re-check installed plugins]    [Skip — show in Settings]   │
└─────────────────────────────────────────────────────────────┘
```

- **Checkbox** is informational ("I've installed this") — clicking does nothing on its own; the source of truth is the filesystem probe.
- **Find online** opens a browser search (`https://www.google.com/search?q=<vendor>+<name>+download`) — Sketchbook does not maintain vendor-URL mappings (out-of-date risk; brand drift) and there's no licensing concern with a generic search.
- **Re-check installed plugins** runs `JvmPluginPresenceProbe`, recomputes `project_plugins.is_installed` + `user_library_plugins.is_installed`, rewrites this host's `plugin_manifest_<host_id>.json`, and refreshes the list. Rows where the latest probe shows installed move to a collapsed "Already installed" section at the bottom.
- **Skip — show in Settings** dismisses; the screen is reachable later from `Settings → Setup → Plugin install status`.

### State + ViewModel

```kotlin
data class PluginChecklistUiState(
    val pending: List<PluginRow>,            // not installed on this OS
    val recentlyInstalled: List<PluginRow>,  // installed since this view first opened
    val alreadyInstalled: List<PluginRow>,   // installed at first load (collapsed by default)
    val isReprobing: Boolean,
)

data class PluginRow(
    val name: String,
    val vendor: String,
    val format: PluginFormat,                 // Vst3 | Vst2 | Au | Component
    val projectCount: Int,
    val templateCount: Int,
    val installed: Boolean,
)
```

ViewModel reads from `MachineProfileStore.composeHostSlice()` (filtered to this OS's compatible formats) on init and after each reprobe. State holder is contributed via Metro per `docs/architecture/dependency-injection.md`; `metroViewModel<PluginChecklistViewModel>()` per the standard pattern.

### Reachability and dismissal

- The screen is reachable from Settings always, regardless of dismissal state.
- A user with zero pending plugins never sees the screen on first launch but can still open it from Settings to view the full plugin map across hosts.
- No "don't show again" — Settings entry is the off-switch; first-launch skip just defers.

## Out of scope (deliberate non-goals)

- **Plugin binaries.** License/legal/size. Sketchbook surfaces what to install, not the binaries themselves.
- **Plugin presets in vendor folders** (`~/Library/Audio/Presets/<vendor>/`, `%APPDATA%\<vendor>`). Vendor-fragmented, OS-divergent paths. Direct users to Dropbox/Syncthing for these specifically.
- **Ableton Packs.** License-anchored, huge, mostly static.
- **Live preferences** (`~/Library/Preferences/Ableton/...`). Per-machine on purpose.
- **Multi-user collab implementation.** `UserId.DEFAULT` placeholder stays; ACL fields exist on the wire but no UI/enforcement is wired. v1.2's job. The design *admits* it; this PR doesn't *do* it.

## Affected files

### Core

- `shared/core/.../core/Ids.kt` — add `TrackedTreeId`.
- `shared/core/.../core/TrackedTreeKind.kt` — new sealed interface.
- `shared/core/.../core/Manifest.kt` — `treeId` + `kind`, `snapshotKind` rename, tombstone field, version 2.
- `shared/core/.../core/CloudDocKey.kt` — new value class.
- `shared/core/.../core/Collaborator.kt` — collaborator + role types for multi-user-ready registry.

### Cloud

- `shared/cloud/.../cloud/CloudBackend.kt` — new manifest signatures + CloudDoc methods.
- `shared/cloud/.../cloud/Lock.kt` — `LeaseLock` gains `ownerUserId`.
- `shared/cloud/jvmMain/.../DirectGcsBackend.kt` — new path templates; v=1 decoder; CloudDoc impl.
- New `shared/cloud/jvmTest/.../V1ManifestBackCompatTest.kt`.
- New `shared/cloud/jvmTest/.../CloudDocTest.kt`.

### Sync

- `shared/sync/.../sync/SnapshotPipeline.kt` — `PipelineInput.uuid` → `treeId` + `kind`; lease step gated by `KindPolicy`; conflict path consults `ConflictMode`; merge-retry loop.
- `shared/sync/.../sync/PullPoller.kt` — `subscribe(treeId, kind, …)`; writes to `tree_snapshots` + `tree_journal` for non-project kinds.
- New `shared/sync/.../sync/KindPolicy.kt`.
- New `shared/sync/.../sync/ConflictMode.kt`.
- New `shared/sync/.../sync/ManifestMerger.kt`.

### Sync-IO

- `shared/sync-io/jvmMain/.../ManifestMaterializer.kt` — `treeRoot: (TrackedTreeId, TrackedTreeKind) -> Path`; tombstone handling (delete materialized file).
- New `shared/sync-io/jvmMain/.../UserLibraryWorkingTree.kt` (uses `UserLibrarySkipSet`).
- New `shared/sync-io/jvmMain/.../UserLibrarySkipSet.kt`.
- `shared/sync-io/jvmMain/.../JvmWorkingTree.kt` — emit tombstones for relpaths present in the parent manifest but absent on disk.

### Repository

- `shared/repository/.../repo/TreeRegistry.kt` — read/write registry CloudDoc; cache mirror; permission helpers (`canRead`, `canWrite`).
- `shared/repository/.../repo/MachineProfileStore.kt` — per-host `plugin_manifest_<host_id>.json` read/write composed from the union of `project_plugins` + `user_library_plugins`; `machines.json`, `ableton_versions.json` read-merge-CAS.
- `shared/repository/.../repo/SettingsRepository.kt` — add `userLibraryRoot: String?`, `migrationComplete: Boolean`, `userLibrarySyncEnabled: Boolean`.
- New `shared/repository/.../repo/TreeJournal.kt` — append-only writer for `tree_journal`.

### Catalog

- `shared/catalog/.../sqldelight/.../10.sqm` — `tree_sync_state`, `tree_registry_cache`, `tree_snapshots`, `tree_journal`, `user_library_plugins`.
- Schema-probe bump in `CatalogDb.kt`.
- New `shared/catalog/jvmMain/.../UserLibraryPluginScanner.kt` — walks materialized UL for `*.als` / `*.adg`, runs `AlsParser`, upserts into `user_library_plugins`. Mirrors the plugin-extraction subset of `JvmScanner`'s project work; the two scanners share the parser but write to different tables.
- `shared/catalog/jvmMain/.../JvmScanner.kt` — also invoked post-materialize for Project trees (currently only on filesystem-watcher events). Both kinds share a single hook: when `tree_journal` records a `materialize` event and `KindPolicy.contributesToPluginManifest = true`, the kind-appropriate scanner runs. Symmetric for Project (`JvmScanner` → `project_plugins`) and UserLibrary (`UserLibraryPluginScanner` → `user_library_plugins`).

### Migration tool

- New `shared/migration/.../CloudMigrator.kt` (commonMain) + `JvmCloudMigrator.kt` (jvmMain). Lives in shared so it can run in-process from the desktop app's startup flow.
- New `app-desktop/.../desktop/ui/migration/MigrationDialog.kt` — Compose UI for the confirmation/preview/progress modal.
- New `app-desktop/.../desktop/migration/MigrationCoordinator.kt` — startup hook that detects legacy paths, drives the dialog, runs the migrator.

### App-desktop

- `UserLibraryWatcher` → `SnapshotPipeline` wiring for the UserLibrary tree, gated on `Settings.userLibrarySyncEnabled` (off by default; flipped on by the post-migration onboarding step once the skip-set spike confirms).
- `MachineProfileWriter` invoked post-scan + post-plugin-probe — writes `plugin_manifest_<host_id>.json`.
- Startup-order: auth → migration coordinator (mandatory) → registry sync → trees + profile → plugin checklist screen (if any pending).
- New `app-desktop/.../desktop/ui/setup/SetupNav.kt` — orchestrates the post-onboarding setup flow.
- New `app-desktop/.../desktop/ui/setup/PluginChecklistScreen.kt` — Compose screen.
- New `app-desktop/.../desktop/ui/setup/PluginChecklistViewModel.kt` — Metro-contributed state holder reading from `MachineProfileStore`.
- New `app-desktop/.../desktop/ui/setup/PluginRow.kt` — row composable + `Find online` action.
- `Settings` screen gains a "Setup → Plugin install status" entry that opens `PluginChecklistScreen` standalone.

### Tests

- `TrackedTreeIdTest` — minting + serialization roundtrip + charset rejection.
- `KindPolicyTest` — every kind has the right flags.
- `ManifestMergerTest` — disjoint relpaths union; same-relpath LWW by mtime; tie-break by hostId; tombstone propagation; deletion-survives-merge regression.
- `SnapshotPipelineTest` — `kind = UserLibrary` skips lease step; merge-retry-on-conflict up to N times; merge-retry-exhaustion fails as Failed.
- `V1ManifestBackCompatTest` — fixture v=1 manifest decodes to v=2 in-memory shape.
- `CloudDocTest` — read/write CAS; list-prefix; not-found returns null.
- `TreeRegistryTest` — concurrent register-tree retry; permission helpers (single-user case = always-true; multi-user-ready case = role enforcement).
- `MachineProfileStoreTest` — host-sliced read/write; union semantics; CAS-retry on machines.json.
- `UserLibraryWorkingTreeTest` — skip-set behavior (each entry has a unit case); tombstone emission on file deletion.
- `UserLibraryPluginScannerTest` — `*.als` and `*.adg` fixtures yield expected `user_library_plugins` rows; idempotent upsert on re-scan; rows for files removed from UL get cleaned.
- `MachineProfileStoreUnionTest` — fixture rows in both `project_plugins` and `user_library_plugins` collapse correctly into the host slice; UL-only plugin appears in the union; duplicates dedup.
- `KindPolicyTest` — extended: `alsPatchingEnabled` and `contributesToPluginManifest` correct for each kind; flipping `alsPatchingEnabled` for UserLibrary triggers AlsPatcher post-materialize.
- `CloudMigratorTest` — dry-run idempotency; partial-migration resume; post-migration registry shape; legacy-paths-cleaned.
- `MigrationCoordinatorTest` — detects legacy → invokes dialog → runs migrator → marks complete; closing without running quits the app (no degraded-mode path).
- `PluginChecklistViewModelTest` — empty pending → screen-skipped signal; reprobe moves a row from pending → recentlyInstalled; OS filter excludes `vst3` rows on Linux et al.
- `SetupNavTest` — startup ordering: auth → migration → checklist → main; checklist skipped when pending is empty; Settings entry opens the screen standalone outside the setup flow.

## Rollout

Stacked commits on `feat/backend-generalization`. Each commit is green standalone:

1. **`refactor(core): TrackedTreeId + TrackedTreeKind + tombstone field + Collaborator`** — types only, default values everywhere existing.
2. **`refactor(cloud): CloudBackend manifest API takes TrackedTreeId+kind; LeaseLock carries userId`** — signature widening; project sites pass `kind = Project`. Wire format still `v=1`.
3. **`feat(cloud): CloudDoc primitive + DirectGcsBackend impl`** — new methods, no callers yet.
4. **`feat(catalog): tree_* tables (10.sqm)`** — `tree_sync_state`, `tree_registry_cache`, `tree_snapshots`, `tree_journal`. Schema-probe bump.
5. **`feat(repo): TreeRegistry + permission helpers + tree_registry_cache mirror`** — registry reads/writes; `canRead`/`canWrite`.
6. **`refactor(sync): KindPolicy + ConflictMode plumbed through pipeline`** — `BranchFork` only mode wired; `Merge` left as `error("not yet")`. Existing project behavior unchanged.
7. **`feat(sync): ManifestMerger + Merge conflict mode + retry loop`** — merger + integration test on fake backend.
8. **`feat(repo): TreeJournal + non-project snapshot persistence`** — `PullPoller` writes `tree_snapshots` + `tree_journal` for non-project kinds.
9. **`feat(cloud): Manifest v=2 wire format + v=1 back-compat decoder`** — writes are v=2 from this commit forward; reads accept both.
10. **`feat(migration): CloudMigrator + MigrationDialog + startup coordinator`** — auto-detects legacy paths, runs migrator on user confirm. Builds initial `registry.json` from local catalog. Registers UserLibrary tree.
11. **`feat(sync-io): UserLibraryWorkingTree + UserLibrarySkipSet + tombstone emission`** — skip-set as decided; tombstones emitted by `JvmWorkingTree` and `UserLibraryWorkingTree`. Watcher/pipeline wiring in app-desktop behind `userLibrarySyncEnabled` flag (off; flipped on after spike).
12. **`feat(catalog): UserLibraryPluginScanner + user_library_plugins`** — walks materialized UL `.als` / `.adg`, populates `user_library_plugins`. Hooked off `tree_journal` materialize event, gated on `KindPolicy.contributesToPluginManifest`.
13. **`feat(repo): MachineProfileStore + host-sliced plugin manifest`** — composes per-host slice from union of `project_plugins` + `user_library_plugins`; wired post-scan + post-probe.
14. **`feat(app-desktop): bootstrap-data wiring`** — registry-pull on launch, UserLibrary materialize on first run, plugin manifest union API + setup-nav scaffolding ready for the checklist screen to land on top.
15. **`feat(app-desktop): plugin checklist screen + Settings entry`** — `PluginChecklistScreen` + `PluginChecklistViewModel` + `SetupNav` integration; Settings → "Plugin install status" entry exposes the same screen post-onboarding.

The skip-set spike happens between commits 10 and 11 — it's a one-launch observation pass with no design dependencies, but tightening the skip-set in the spike PR keeps commit 11's tests aligned with reality.

The v=1 decoder drop (when `machines.json` shows all hosts migrated) is a follow-up PR after this branch ships, not a commit here.

Each commit ships green builds + green tests; each compiles standalone.

---

User reviews + merges manually. Doc deleted post-merge per no-standing-plans rule.
