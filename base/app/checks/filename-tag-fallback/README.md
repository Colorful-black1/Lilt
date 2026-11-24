# Filename tag fallback

## Problem

Many files are named `Artist - Title` but carry no Title or Artist tag. The
scan then showed the whole filename as the title and `Unknown artist` as the
artist. On Windows Explorer the Name column still shows the filename, which made
the files look tagged.

A sample of the user's flat `D:\音乐` folder (828 audio files) found:

```text
Title missing      307
Artist missing     305
Both missing       298
Of the 298, filenames matching "Artist - Title" or "Artist-Title": 296
```

## Fix

`base/app/src/library.rs` and the Android
`library/FilenameTags.kt` now derive display tags from the filename only when
the embedded/MediaStore value is blank, a placeholder, or identical to the
filename. Real tags and per-song tag overrides still win, and no audio file is
modified.

Supported shapes:

| Filename | Track | Artist | Title |
|---|---:|---|---|
| `周杰伦 - 晴天` | 0 | 周杰伦 | 晴天 |
| `Aimer-After Rain` | 0 | Aimer | After Rain |
| `01. 周杰伦 - 晴天` | 1 | 周杰伦 | 晴天 |
| `(02) 周杰伦-晴天` | 2 | 周杰伦 | 晴天 |
| `All Time Low - Time-Bomb` | 0 | All Time Low | Time-Bomb |
| `AC-DC - Back in Black` | 0 | AC-DC | Back in Black |
| `7 Rings` | 0 | — | 7 Rings |
| `1984` | 0 | — | 1984 |
| `忘记时间_20200523220957` | 0 | — | 忘记时间 20200523220957 |

The parser accepts ` - `, `–`, `—`, `－` and unspaced hyphens. A leading number
is treated as a track number only when it is followed by punctuation or, for a
space-only separator, is zero-padded. The old PC fallback that used the
grandparent folder as the artist was removed; a failed guess now shows
`Unknown artist`.

## Verification

```text
PC:      67 passed, 0 failed, 1 ignored
Android: 60 passed, 0 failed
```

`01-pc-library-fallback.png` is the release PC build scanning a private folder
containing copies of real untagged files. It shows:

- `After Rain` / `Aimer`
- `LAST STARDUST` / `Aimer`
- `One` / `Aimer`
- `Time-Bomb` / `All Time Low`
- `晴天` / `周杰伦` for `01. 周杰伦 - 晴天` and `(02) 周杰伦-晴天`
- `7 Rings` / `Unknown artist` (not track 7)

Android uses the same table in `FilenameTagsTest` and is integrated in
`MusicRepository.scan`; the Room schema is unchanged.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,935,232 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,471,701 bytes
base/app/Lilt-1.0-debug.apk           22,226,620 bytes
```

## Evidence

| File | What it shows |
|---|---|
| `01-pc-library-fallback.png` | PC song list with parsed artists/titles for untagged files |
| `02-android-library-fallback.png` | Android library list with `After Rain / Aimer` and `7 Rings / Unknown artist` |
| `03-android-search-time-bomb.png` | Android search result with `Time-Bomb / All Time Low` |
| `android-permissions.txt` | Final APK permission dump; no new permission |
| `pc-tests.txt` | PC unit-test summary |
| `android-tests.txt` | Android unit-test summary |
