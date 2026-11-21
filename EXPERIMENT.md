# Experimental wrapped-HLS playback test

This branch builds a **separate** Android TV app with the application ID `net.mrowser.experimental`.
It must not be merged before confirming the actual segment format and successful playback.

## Why this exists

The Sony XBR-65X850D (Android 8) reported:

```
ParserException: Cannot find sync byte. Most likely not a Transport Stream.
  at TsExtractor.findEndOfFirstTsPacketInBuffer
```

An HLS playlist references extensionless objects on a TikTok CDN. The current experimental
DataSource skips conventional PNG or JPEG image prefixes when followed by video bytes;
it **does not** decode image pixels or decrypt content.

## After successful GitHub Actions build

1. Download the `mrowser-debug-apk` Actions artifact on the phone.
2. Extract its APK and sideload it onto the Sony via Bugjaeger or Downloader.
3. Open the test app (the original mrowser remains installed).
4. Reproduce the stream error and inspect:

```sh
logcat -d -v time | grep -iE 'WrappedImageSegment|ExoPlayerImplInternal|ParserException'
```

Redact stream tokens, cookies and private URLs before sharing logs.

If the log reports a PNG or JPEG wrapper and the payload starts with `47`, that's
consistent with an MPEG-TS sync byte. Other payload starts may require a different decoder.
