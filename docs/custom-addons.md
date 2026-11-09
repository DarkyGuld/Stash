# Custom Eclipse addons

An addon is a small HTTPS service. Stash installs it by URL, fetches its
manifest, and then calls the declared endpoints directly. There is no Stash
server and no registration step.

## Manifest

```
https://your-host.example/addon/<token>/manifest.json
```

The URL must be HTTPS. Stash derives every other call from this URL, so a token
in the path (`/addon/<token>/manifest.json`) or in the query (`?token=...`) is
preserved on every request. Do not put secrets in a path you are not prepared
to treat as a credential; the addon URL is the credential.

Minimal manifest:

```json
{
  "id": "my-addon",
  "name": "My Addon",
  "version": "1.0.0",
  "description": "Optional",
  "icon": "https://your-host.example/icon.png",
  "resources": ["search", "resolve", "stream"]
}
```

`resources` must contain `stream` and at least one of `search`, `resolve` or
`isrc`. Unknown keys are ignored. `settings` is optional: an array of
`{key,type,label,help,default,perNetwork,options,maxLength,min,max,step}` or an
object `{key: {...}}`; a primitive value is shorthand for its default. Setting
defaults are sent as query parameters on every request.

The manifest is snapshotted at install time. To change resources or settings,
remove the addon and add it again; there is no refresh action.

## Endpoints

All paths are relative to the manifest's directory. All receive the manifest
URL's token/path/query plus the default settings as query parameters.

### `GET /search?q=<query>`

Return `{"tracks":[...]}` or a bare array. Each track:

```json
{
  "id": "123",
  "title": "Song",
  "artist": "Artist",
  "album": "Album",
  "duration": 245,
  "artworkURL": "https://...",
  "isrc": "US...",
  "format": "flac",
  "streamURL": "https://..."
}
```

`duration` is seconds; values >= 10000 are treated as milliseconds. `id` must
be non-blank. `streamURL` is optional; `/stream/{id}` is the normal path.

### `GET /resolve?title=<title>&artist=<artist>&durationMs=<ms>&isrc=<isrc>`

Return `{"item":{...}}`, a bare track object, or `{"item":null}` for a miss.
`isrc` is authoritative when present; title/artist/duration are the fallback
key. Eclipse Cloud also accepts `quality`.

### `GET /resolve-isrc?isrc=<isrc>`

Return `{"trackId":"..."}` or `{"id":"..."}`. A 403/404 is a normal miss.

### `GET /stream/<trackId>` (or `GET /stream?trackId=<id>`)

Return a stream object:

```json
{
  "url": "https://.../audio.flac",
  "format": "flac",
  "quality": "LOSSLESS",
  "expiresAt": 1794182400,
  "codec": "flac",
  "container": "flac",
  "manifest": "none",
  "sampleRate": 44100,
  "bitDepth": 16
}
```

- `manifest`: `none`, `hls` or `dash`; a `.m3u8` URL also counts as HLS.
- `expiresAt`: Unix seconds, or milliseconds when the value is large.
- `codec`/`format`/URL extension: flac, mp3, m4a, aac, opus, ogg, wav.
- Without expiry, Stash caches progressive URLs for 30 min and HLS for 6 h.

## Headers and auth

Stash sends, on every third-party addon call:

```
User-Agent: Stash/<version>
X-Client-Platform: web
```

There is no custom-header or OAuth support for third-party addons. The only
credential channel is the manifest URL's path/query token. `Authorization` and
`X-Eclipse-Access` are sent only to Eclipse's own `api.eclipsemusic.app` host;
a third-party addon never sees them.

## Errors and timeouts

- `503` -> `Preparing`, with optional `Retry-After` (capped at 30 s); the
  stream is retried once.
- `403`/`404` on `/resolve` or `/resolve-isrc` -> normal miss.
- Other non-2xx -> failure; the resolver moves to the next addon.
- Timeouts: 8 s for metadata, 30 s for streams.
- `IOException`/timeout -> unreachable; malformed JSON -> invalid response.
- A per-addon failure is never fatal; the next addon in the walk is tried.

## Downloads

Playback accepts HLS/DASH/progressive. Downloads accept only a finite,
unencrypted, non-master HLS media playlist with `#EXT-X-ENDLIST` and at most 500
segments (see `HlsPlaylistParser`). Master playlists, live playlists and
encrypted HLS play in the player but are refused as downloads.

## Test locally

1. Serve the manifest and endpoints over HTTPS.
2. In Stash: Settings -> Lossless sources -> Add addon, paste the manifest URL.
3. Stash validates `id`, `name`, `resources` and reachability.
4. Search for a track, play it, then try a download.

## Limits

- No official Spotify/Apple catalog routes for third-party addons; the Apple
  route is hard-coded to Eclipse Cloud.
- No manifest refresh/URL edit; remove and re-add.
- No pagination; the client applies a limit locally.
- The addon URL is the secret; do not log it or share it.
