# libmpv Android integration notes

This file records the mpv/libmpv API details that affect the Android MPV player
integration in this project.

## loadfile command

Official syntax from `DOCS/man/input.rst`:

```text
loadfile <url> [<flags> [<index> [<options>]]]
```

Relevant details:

- The second argument is the playlist action, usually `replace`.
- Since mpv 0.38.0, the third argument is a playlist insertion index.
- Per-file options are the fourth argument, formatted as
  `opt1=value1,opt2=value2`.
- If per-file options are needed with `replace`, the third argument must be
  `-1`.

Correct HLS-forced load command:

```java
MPVLib.command(new String[] {
        "loadfile",
        url,
        "replace",
        "-1",
        "demuxer=lavf,demuxer-lavf-format=hls"
});
```

Incorrect command:

```java
MPVLib.command(new String[] {
        "loadfile",
        url,
        "replace",
        "demuxer=lavf,demuxer-lavf-format=hls"
});
```

The incorrect form passes the options string as the insertion index and produces:

```text
The loadfile option must be an integer
```

## State mapping

Do not treat `MPV_EVENT_FILE_LOADED` as Media3 `STATE_READY` by itself. A file can
load and immediately fail before a useful audio/video frame is produced. Prefer
waiting for `MPV_EVENT_PLAYBACK_RESTART` before reporting ready playback.

`MPV_EVENT_END_FILE` can mean success or failure. The bundled JNI now forwards
`mpv_event_end_file.reason` and `mpv_event_end_file.error` through
`MPVLib.endFile(reason, error, errorText)`. Java should use this structured
native event first, and keep mpv logs/properties only as secondary classification
signals for user-facing error messages.

## Current HLS limitation

The existing Exo/Media3 stack in this repo is patched for HLS edge cases, notably:

- `third_party/patches/media3-sample-aes-identity.patch`
- `third_party/patches/media3-hls-pes-synthesized-pusi-quiet.patch`

Some sources rely on these patches. In logs they can appear to libmpv/FFmpeg as
HLS streams whose media samples are detected as `Video: png`, followed by:

```text
Invalid data found when processing input
no audio or video data played
```

Passing these URLs directly to libmpv is not enough. MPV support for those sources
requires an equivalent local HLS decrypt/remux proxy or native demuxer support.
