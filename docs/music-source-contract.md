# Read-only music-source contract (offline implementation)

Default production source remains the existing CarPlay bridge. No MediaSession observer,
notification listener, permission request, notification-body collection, playback command,
traffic-light SDK, vehicle operation, or new music account access is introduced.
`CarPlayMediaSessionService` remains the outbound bridge, not a native-player observer.

`UltraMusicSources` selects one complete immutable snapshot: source/package/session/item,
title, artist, artwork, playback clock and lyrics. Native use requires explicit
`setNativeMusicOptIn(true)`, an already-authorized external reader, and a captured
`nativeMusicEpoch()` retained with asynchronous work. `submitNativeMusic(epoch, snapshot)`
rejects stale leases. Call `nativeMusicUnavailable()` on access loss/session destruction;
disable and disconnect also invalidate leases. No device access is enabled by these APIs.
Only `com.xiaopeng.musicradio` is accepted by the native candidate adapter. Its
`lyricInfo` JSON / `lyricContent` standard-LRC contract comes from supplied documents,
**not runtime verification**. The missing observer/access evidence is deliberately not
filled with guessed permissions or a NotificationListenerService.

Native selection is explicit and preferred while a complete snapshot is fresh (1,500 ms).
Expiry/loss falls back to a complete CarPlay snapshot; it never borrows CarPlay cover or
clock for native lyrics. Invalid/unknown clocks retain metadata but clear lyric timing.
The native owner must supply monotonic observation and PlaybackState anchor times;
future/zero anchors, invalid speed/position/duration and UNKNOWN state cannot drive lyrics.
PLAYING uses speed in [0,4]; pause and buffering freeze position. Seek reanchors, and
paused lyric data expires too. This conservative freshness policy requires timely owner
observations; it is not a claim that a real MediaSession provides 300 ms callbacks.

CarPlay is sampled from the existing connected process-local bridge every 300 ms; the
sample's freshness represents **bridge liveness**, not a new remote metadata heartbeat.
CarPlay still has no real lyrics. Active media-session begin/end resets metadata, artwork,
clock accumulation and session identity even when its owning Controller stays alive;
controller attachment alone is not considered a connected media source. Each iAP2 media-item
attribute event increments a conservative item revision, so same-title events reset lyrics
and cover ownership. Artwork transfer SETUP captures an ownership epoch, checked again at
completion so a reused transfer ID cannot attach late bytes to the next item. Queue-index changes also change identity. Repeated item-attribute
events may intentionally over-invalidate; no unobservable item boundary is invented.
No stable phone package ID is available, so CarPlay packageName is explicitly empty.

The bounded LRC parser accepts at most 65,536 input/expanded text characters, 512 time
entries and 512 characters per line. It sorts times; last duplicate wins; blank timed
entries end preceding text. Intermediate lines end at the next timestamp. The final line
ends only at a known valid duration; otherwise it is omitted, never given a fabricated
end. Nonzero offsets and enhanced word-LRC are explicitly unsupported. No synthetic
word/character timing is derived from line durations. JSON size and nesting are bounded.
Source/session/item changes, permission loss and disconnect invalidate generations.
The production typography and 10 fps / assumed 100 Mbps budget are unchanged.

Offline commands: `bash scripts/test-music-sources-standalone.sh`,
`bash scripts/test-lrc-standalone.sh`, `bash scripts/test-lyrics-standalone.sh`.
The same replay suites plus native JSON tests run in the Gradle unit suite. These tests
are not evidence of real lyric access, APK permissions, vehicle rendering or compatibility.
