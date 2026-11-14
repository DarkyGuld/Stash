# Stash — architecture

The Stash app is a modular Android app (Kotlin, Compose, Media3, Hilt,
Room). This document is the map: what each module owns, how a song gets
from a tap to a sound, and where the add-on integration sits.

```
┌──────────────────────────────────────────────────────────────────────┐
│ feature/*        Compose screens + ViewModels                        │
│   home · library · nowplaying · search · settings · record · …      │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ StateFlow<PlayerState>
┌────────────────────────────────▼─────────────────────────────────────┐
│ core/media       the playback engine                                │
│   PlayerRepositoryImpl · StashPlaybackService (MediaSession)        │
│   streaming/  (the resolver chain, the caches, the data sources)     │
└───────────────┬──────────────────────────────┬──────────────────────┘
                │                              │
┌───────────────▼──────────────┐   ┌───────────▼──────────────────────┐
│ core/data   Room + DataStore  │   │ core/ui    the design system      │
│   library · addons · prefs    │   │ core/model the domain models      │
│   sync · social · radio · …   │   │ core/common the shared helpers    │
└───────────────────────────────┘   └──────────────────────────────────┘
                │
┌───────────────▼───────────────┐
│ data/*    the source clients   │
│   spotify · ytmusic · lyrics   │
│   download · cloud · soundcloud│
└───────────────────────────────┘
```

---

## 1. The modules

| Module | Lines | Owns |
| --- | --- | --- |
| `app` | 6 k | The application class, the DI entry point, the main activity, the release build (signing, version). |
| `core/media` | 32 k | **The playback engine.** The player repository, the MediaSession service, the resolver chain, the stream caches, the data sources, the queue, the radio, the preview player, the diagnostics. |
| `core/data` | 75 k | The library: Room (entities, DAOs, migrations), DataStore (preferences), the add-on client and repository, the sync, the social integrations, the radio generator. |
| `core/ui` | 9 k | The design system: `GlassCard`, `StashSwitch`, `FlacBadge`, the reorderable column, the theme. |
| `core/model` | 2 k | The domain models (`Track`, `Artist`, `Album`) and the pure rules over them. |
| `core/common` | 2 k | The shared helpers (art URLs, constants, the artwork upgrader). |
| `core/auth`, `core/network` | small | The token store and the HTTP stack. |
| `data/spotify`, `data/ytmusic`, `data/lyrics`, `data/cloud`, `data/soundcloud`, `data/download` | 25 k+ | The source clients: the Spotify API, YouTube Music, the lyrics providers, the cloud drives, the SoundCloud bridge, and the lossless download pipeline. |
| `feature/*` | 23 k+ each | The screens: Home, Library, Now Playing, Search, Settings, Record, Community, Listening, Sync. Each is a Compose screen plus a ViewModel. |

The dependency rule: `feature/*` may use `core/*` and `data/*`; `core/media`
may use `core/data` and `core/model`; `core/data` knows nothing about the
UI. Nothing depends on `feature/*`.

---

## 2. The playback engine

### The two sides

* **`PlayerRepositoryImpl`** (≈5 000 lines, `core/media`) is the app's view
  of the player: it owns the queue, resolves what to play, publishes
  `PlayerState` and drives the media controller. It is the only place that
  decides *what* plays.
* **`StashPlaybackService`** (the MediaSession service) owns the actual
  ExoPlayer. It is what Android Auto, the notification and the headset talk
  to. It resolves the queue, picks the media sources and plays.

The app talks to the service through a `MediaController`; the service talks
back through a `Player.Listener`. The repository is the brain, the service
is the mouth and the ears.

### A tap becomes a sound

1. `PlayerRepositoryImpl.playTrack()` → `buildMediaItemForTrack()`.
2. The routing decision tree: a local file that is really on disk wins
   outright (no network, works in airplane mode); then Download mode, the
   connectivity, the cellular preference; then the URL cache; then the
   resolver chain.
3. The MediaItem goes to the controller (`setMediaItems`) → the service
   resolves it → the media source factory opens the source → ExoPlayer
   plays.
4. Every player event lands in `updateState()`, which publishes the new
   `PlayerState`. The UI collects it; it never polls.

### The stream caches

| Cache | Where | What it holds |
| --- | --- | --- |
| `StreamUrlCache` | RAM (LRU, 512) | The resolved URL per track, with a TTL from the URL's own expiry. |
| `EclipseStreamUrlStore` | Disk (DataStore) | The same, so a restart does not need a new resolve. |
| `EclipseAddonResolveCache` | RAM (LRU, 256) | The add-on's item id per track and settings — the metadata round trip that does not change between plays. |
| `StreamCache` | Disk (Media3 `SimpleCache`) | The audio bytes. |
| `TorboxPointerEntity` | Room | The phone's own TorBox pointers: MD5 → infohash + torrent id. |

The rule that keeps them honest: **a file below the target quality is never
served from a cache.** It is a reserve — the add-on may have a better file
now — so the add-on is asked again and the old entry is dropped. A file the
add-on cannot replace (a failed resolve) is still played: a stale file
beats no file, as long as its add-on is still enabled.

---

## 3. The resolver chain

`StreamSourceRegistry` (`core/media/streaming`) walks the sources in order
and returns the first that answers. The order depends on the settings:

```
"Play from Eclipse addons first" on:   addons → tidal/qbdlx → jiosaavn → youtube
otherwise:                              tidal/qbdlx → addons → jiosaavn → youtube
```

| Source | What it is |
| --- | --- |
| **Qobuz** (`QobuzStreamResolver`, `QbdlxStreamResolver`) | The direct-Qobuz lossless path. |
| **Tidal** (`TidalStreamResolver`) | The user's own BitChord server. |
| **Eclipse add-ons** (`EclipseAddonStreamResolver`) | The installed add-ons, in the user's order. |
| **JioSaavn** | The lossy fallback. |
| **YouTube** (`YouTubeStreamResolver`) | The last resort (yt-dlp / InnerTube). |

Two rules the chain follows:

* **The add-on leads when the user says so.** With "play from add-ons first"
  the parallel YouTube lane is switched off: a parallel lane can answer
  before the add-on does, and then the add-on is never heard from — even
  when it would have had the file. The sequence gives the add-on its full
  chance, and YouTube is only the fallback.
* **A "preparing" add-on is waited out, not skipped.** An add-on that
  answers 503 while it installs the file is polled every 1.5 s for six
  seconds. A preparing answer is not a failure: the add-on is working.

---

## 4. The add-on integration

An add-on is an HTTP service that declares a manifest:

```json
{
  "id": "nullbeat-cache",
  "name": "NullBeat Cache",
  "resources": ["search", "stream", "resolve", "isrc", "settings"],
  "settings": [
    { "key": "quality", "type": "select", "default": "max", "options": [...] }
  ]
}
```

* **The client** (`EclipseAddonClient`, `core/data/addons`) speaks the
  protocol. The add-on's settings travel as query parameters on every
  request. A setting marked `local` (a TorBox API key) never leaves the
  phone.
* **The repository** (`EclipseAddonRepository`) owns the installed list, the
  settings, the order and the generations. The list lives in a DataStore as
  one JSON array; the order is the `order` field.
* **The resolver** (`EclipseAddonStreamResolver`, `core/media/streaming`)
  resolves a track to a stream: the item id, the stream URL, and the tier
  the file is in.

### The NullBeat Cache add-on

The NullBeat Cache V2 server is itself an add-on. The phone talks to it
over `/stash/resolve` and `/stash/stream`, and the server answers with the
file it has in its TorBox account.

The phone can also play the file from **its own** TorBox account: the
add-on's answer carries the file's infohash, and the phone adds that file
to its own account (`TorboxLocalResolver` + the Room pointer table). The
server never learns anything about the phone's account.

### The quiet quality upgrade

A song that starts on a reserve file (the add-on was still installing it,
or the chain fell through to a lossy fallback) gets a quiet watch:

```
every 3 s, at most four times
  → the add-on is asked again, with the caches bypassed
  → the better file is resolved to a direct url
  → its bytes are written into the player's own cache around the current
    position
  → only then is the MediaItem replaced, at the exact position
```

Two things end the watch without a swap: the song is more than three
quarters done (a swap there costs more than it gives), or the pre-buffer
fails or takes too long (the song keeps what it has, and the next play
starts on the better one).

The badge follows the live stream: it reads the cache, not the item's
extras — the extras of a replaced item do not survive the session round
trip.

---

## 5. The data layer

**Room** (`core/data/db`) holds the library: tracks, albums, artists,
playlists, likes, the listening history, the TorBox pointers. The schema
version is bumped with every change, and the migration is written by hand
(`MIGRATION_78_79` and its neighbours) — the JSON snapshots in
`core/data/schemas/` are the record of every version.

**DataStore** holds the preferences: the streaming settings, the theme, the
home layout, the installed add-ons, the data-saver switch.

**The sync** (`core/data/sync`) mirrors the library with a server (the
Web Library), with Spotify and with YouTube Music. It runs as WorkManager
jobs and writes through the same DAOs.

---

## 6. The screens

| Feature | What it does |
| --- | --- |
| `feature/home` | The start screen: the mixes, the record, the discovery rows. The order is user-draggable (`HomeSectionsPreference`). |
| `feature/library` | The library: albums, artists, playlists, the songs. |
| `feature/nowplaying` | The player, the queue, the Up-next sheet, the mini player. The quality line ("FLAC · 24-bit/96 kHz · via Nebula") is built by `TrackQualityText`. |
| `feature/search` | The search, with the preview player. |
| `feature/settings` | Every setting, including the lossless sources and the add-on list. |
| `feature/record` | The listening record: the stats, the streaks, the supporter badge. |
| `feature/community` | The shared playlists and the following. |
| `feature/listening` | The Listen Together sessions. |
| `feature/sync` | The sync status and the account linking. |

---

## 7. Build and release

* **Gradle**: `settings.gradle.kts` lists the modules; `build-logic/` is an
  included build with the convention plugins (so every module gets the same
  Kotlin, Compose and lint setup).
* **Versions**: `app/build.gradle.kts` holds `versionCode` and `versionName`.
* **Signing**: `keystore.properties` (gitignored) or the `STASH_KEYSTORE_*`
  environment variables. The release key is `CN=Stash Release`; every
  release is signed with it, so a new version installs over the old one
  without losing anything.
* **The APK**: `./gradlew :app:assembleRelease` →
  `app/build/outputs/apk/release/app-release.apk`.
* **Tests**: `./gradlew testDebugUnitTest` — 9 250 tests. The module that
  owns a rule tests it there: the resolver rules in `core/media`, the
  library in `core/data`, the screens in `feature/*`.
