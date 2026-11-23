# PC crash fix — M4A/MP4 playback

## Symptom

Clicking **Play** on some local songs closed the desktop program immediately. Starting
the same song with `LILT_AUTOPLAY=1` produced:

```text
thread 'main' panicked at ...\rodio-0.20.1\src\decoder\symphonia.rs:45:21:
internal error: entered unreachable code: Seek errors should not occur during initialization
```

The crash happened before any audio started. It was not an audio-device, queue or
state-file problem.

## Root cause

Rodio 0.20 wraps every decoder reader in `ReadSeekSource`:

- `is_seekable()` returns `true`;
- `byte_len()` returns `None`.

Symphonia's ISO/MP4 reader needs the byte length when the stream is seekable. A missing
length becomes `Error::SeekError(Unseekable)` during `FormatReader::try_new`. Rodio's
decoder constructor treats any seek error during initialization as impossible and calls
`unreachable!()`, which aborts the process.

Every MP4/M4A file hit this path, including M4A audio saved with a `.mp3` name. A scan
of 828 local files found 18 such files.

A second issue made those misnamed files show `0:00`, `0 kHz` and `0 kb/s`: Lofty's
probe selected the parser from the extension, so an M4A named `.mp3` was treated as
MPEG and its properties were not read.

## Fix

1. `src/playback_source.rs` now decodes local files with Symphonia directly. It gives
   Symphonia the real `File` media source, whose `byte_len()` is known, so MP4/M4A
   playback works. The decoder:
   - reports unsupported/damaged files as ordinary errors;
   - retries a short run of damaged packets;
   - implements sample-accurate seeking;
   - wraps construction in `catch_unwind` so a third-party panic cannot close the app.
2. `make_source` in `src/audio.rs` uses this decoder instead of `rodio::Decoder`.
3. `src/library.rs` now probes tags and properties by file content first, then falls
   back to extension probing. M4A data named `.mp3` gets its real duration, sample rate,
   channels, bitrate and an `M4A` label.

No Android code changed.

## Verification

```text
cargo test
# 41 passed, 0 failed
```

- **Playback proof.** A 20-second AAC/M4A file was named `01 - Mislabelled M4A.mp3`
  and placed in a temporary library. Lilt started playback at `0:07 / 0:20`, showing
  `M4A · 44 kHz · mono · 96 kb/s` and the real path. See
  `01-pc-m4a-in-mp3-name.png`.
- **Real library.** The same 828-file library was scanned through the new decoder:
  826 files opened and seeked; one DRM-protected `.kgm.flac` is still unsupported, and
  one MP3 plays from the start but reports a seek error at 80 %.
- **Damaged input.** A 4 KB random file with an `.mp3` name stayed on the player screen,
  paused at `0:00`, and the process stayed alive. See
  `02-pc-damaged-file-survives.png` and
  `playback_source::tests::damaged_files_return_an_error_instead_of_panicking`.
- **Regression coverage.** WAV decode/seek and M4A decode/seek are unit tests. The
  content-aware metadata path is tested by copying the M4A fixture to `.mp3` and reading
  it through `read_song`.

## Files

| File | What it shows |
|---|---|
| `01-pc-m4a-in-mp3-name.png` | M4A content with a `.mp3` name playing at 0:07 / 0:20 |
| `02-pc-damaged-file-survives.png` | Damaged file leaves the app alive and paused |
| `before-panic.txt` | Original Rodio `unreachable!()` panic |
| `pc-tests.txt` | 41-test result and new test names |

## Limitations

- A DRM-protected/encrypted file still cannot be decoded; it now shows an error and the
  program stays open.
- One MP3 in the tested library plays from the beginning but cannot seek reliably. Seek
  failures are ignored by the UI, as before, and no longer affect playback startup.
- Symphonia does not cover APE, DSD or WMA. Those remain outside the desktop format
  list.
