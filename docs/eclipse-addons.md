# Eclipse addons

Stash can install Eclipse's account-bound `Cloud` addon and third-party addons
(for example Nebula) as lossless streaming sources for Spotify-synced tracks.

## Features

- Addon install/validation, per-addon enable/reorder/remove and per-addon settings.
- Cloud: Tidal `/resolve` + `/stream`, and an Apple Music fallback using the
  public iTunes Search API plus Cloud's `/applemusic/stream` route.
- Fast mode: Tidal and Apple lookups run in parallel. Tidal wins when it has the
  exact item; Apple starts early only after a strict, unique match.
- Direct Qobuz FLAC when the addon returns it; Tidal HLS otherwise.
- Persistent Apple-ID and resolved-URL caches, HLS manifest/segment cache,
  2 MB first-chunk warm, spare-player preload, manual-next hard cut, and
  search/list/Home warmup.
- Downloads: direct Qobuz FLAC as `.flac`; HLS as fragmented-MP4 FLAC `.m4a`
  (`#EXT-X-MAP` init + media segments concatenated).
- Missing ISRCs are fetched from Spotify in batches (50 ids/request) or from
  Deezer, then cached in the track row.

## Settings

- **Lossless sources**: addon URL, Eclipse login (Google/Discord/email or token
  paste), Cloud fastest source, play addons first.
- **Stream cache**: current size, **Clear cache**, **Prefetch whole files**
  (off by default; Wi-Fi only, 60 MB cap, can compete with playback).
- Per-addon quality and other settings via the addon's gear.

## Caches and invalidation

- Apple ID: positive entries do not expire; they are dropped on 401/403/404 or
  a strict-match failure. Negative entries are short-lived and never used for a
  foreground play.
- Resolved URL: kept until the signed URL's `exp`/`etsp`; a disabled or removed
  addon is never served from cache.
- Stream cache: 500 MB LRU under `cacheDir/stream_cache`, holding progressive
  bytes and HLS manifests/segments. 401/403/410 evicts the track's entries.
- Cleared on addon add/remove/toggle/settings change, sign-out, or **Clear
  cache**. Signed URLs and tokens are redacted from logs.

## Performance (measured on a Samsung SM-S938B)

| Scenario | Typical |
|---|---|
| Warm replay, direct Qobuz | 100–300 ms |
| Warm replay, HLS | 105–300 ms |
| Next track, prepared spare | 0–50 ms |
| Auto-advance | 0–10 ms |
| Search/list/Home tap, warmed | 100–300 ms |
| Cold first play | 0.8–2.0 s |
| Random skip, not preloaded | ~400 ms |
| Cold app start | 200–500 ms |

Cold first play is bound by the addon's resolve and the first byte from
Qobuz/Tidal; HLS tracks additionally pay the manifest and first segment.
Neither Eclipse nor Stash can remove that without a prior cache or download.

## Limitations

- Spotify/Apple Music DRM playback is not supported. Eclipse may play some
  tracks through those services; Stash cannot.
- Tracks absent from Tidal, Qobuz, the enabled addons and YouTube are skipped
  after one attempt with a clear message.
- HLS downloads keep the FLAC audio in an `.m4a` container.
- A cold first play cannot be instant without a previous cache or download.
