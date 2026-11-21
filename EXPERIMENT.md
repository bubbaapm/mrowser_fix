# Experimental mrowser: KWP1 HLS playback and in-app updates

This branch builds an Android TV application with ID `net.mrowser.experimental`,
separate from the original `net.mrowser`. The fork's release workflow publishes
APK assets signed with a persistent private keystore.

## Verified playback

Some HLS playlists reference PNG images on `p16.tiktokcdn.com/obj/tiktok-obj/`.
These images contain MPEG-TS bytes in their decoded RGB pixels rather than
appending TS data after the PNG's IEND chunk.

The first eight RGB bytes are ASCII `KWP1`, followed by a 32-bit big-endian
MPEG-TS byte length. The payload immediately follows and its TS packets begin
with a 0x47 synchronization byte every 188 bytes.

The experimental `WrappedImageSegmentDataSource` decodes the target PNG into a
bitmap, extracts the payload in small pixel stripes, validates TS packet alignment
and passes the resulting MPEG-TS bytes to ExoPlayer. Other URLs pass through.

A 12-segment, ~25-second replay played with audio/video on the Sony BRAVIA
XBR-65X850D (Android TV 8) without reported playback errors. The optimized
decoder was subsequently retested successfully on version 1.5.10.

Replay playlist:
`https://raw.githubusercontent.com/bubbaapm/mrowser_fix/experiment/wrapped-hls-segments/diagnostics/previous-25-second-test.m3u8`

Useful device diagnostics:

```sh
logcat -c
am start -a android.intent.action.VIEW -d 'https://raw.githubusercontent.com/bubbaapm/mrowser_fix/experiment/wrapped-hls-segments/diagnostics/previous-25-second-test.m3u8' -n net.mrowser.experimental/net.mrowser.MainActivity
logcat -d -v time -s WrappedImageSegment:I ExoPlayerImplInternal:E MediaCodecRenderer:E
dumpsys meminfo net.mrowser.experimental
```

## In-app update test

1. Confirm installed version 1.5.10: `dumpsys package net.mrowser.experimental | grep -E 'versionCode|versionName'`.
2. A subsequent commit to this branch triggers `.github/workflows/experimental-updates.yml`.
   It tests the app, signs a release APK with the same private keystore and publishes
   it as a GitHub Release named `v1.5.<run-number>`.
3. On the TV, fully close and reopen experimental mrowser after the new release
   exists. On the home screen, choose the **Update available** banner.
4. Choose **Download**. Once the download completes, Android's package installer
   should open for explicit user approval. If Android needs permission to install
   unknown apps, grant it, then return and choose **Install update**.
5. Verify that the new `versionName` and `versionCode` increased, and that
   settings and favorites were retained. A successful update uses the same APK
   signature and does not require uninstalling the previous signed version.

The updater checks GitHub Releases on launch, throttled to one fetch per five
minutes for this experimental fork. It does not monitor GitHub continuously
while the app remains open.

## ART speed compilation

Android 8 may change its dex compilation filter following an APK update. An ADB
shell can inspect and, optionally, force ahead-of-time compilation:

```sh
dumpsys package net.mrowser.experimental | grep -i 'compilation_filter'
cmd package compile -m speed -f net.mrowser.experimental
```

This is a shell command, not a privilege the ordinary Android application can
execute automatically.

Do not commit signing keys or release secrets to the repository.
