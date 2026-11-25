# In-app update check

Both builds now have a manual **Check for updates** action in About. The check uses the
public GitHub Releases API for `Colorful-black1/Lilt`; when a newer tag exists the package
is downloaded automatically. The GitHub URL is shown directly as a link, not as a
separate "Release page" button.

## Version

The update-check feature shipped in the 0.2.1 round so the shared release tag
`v0.2.1` works on both platforms:

```text
PC Cargo.toml            0.2.1
Android versionName      0.2.1
Android versionCode      3
```

The update screenshots below were captured during the 0.2.0 update-check round;
the current builds show 0.2.1.

## PC flow

- About shows the current version and the GitHub Releases URL.
- **Check for updates** starts a background thread; the UI stays responsive.
- The thread reads `tag_name`, compares numeric versions and downloads the first
  `*-portable.zip` asset.
- Downloads go to the first writable location of `Downloads/Lilt Updates`,
  `<data dir>/updates` or `%TEMP%/Lilt Updates`. The actual path is shown with an
  **Open folder** button.
- The running executable is not replaced or restarted.
- No release yet: the live check shows **No release has been published yet.**

## Android flow

- About shows the current version and the GitHub Releases URL.
- The check runs on `Dispatchers.IO`; the APK is downloaded automatically to
  `files/updates/`.
- After download, **Install update** opens the system package installer through
  `FileProvider`. Android asks for confirmation; if "install unknown apps" is not allowed,
  the app opens that settings page and asks the user to tap Install again.
- `REQUEST_INSTALL_PACKAGES`, the FileProvider, and `res/xml/file_paths.xml` were added.
- The project debug keystore is committed as `android/keystore/lilt-debug.keystore` and
  configured for both debug and release builds. This keeps the signing certificate stable,
  so a later release APK can be installed over the current sideloaded debug build.

## Evidence

| File | What it shows |
|---|---|
| `01-pc-about.png` | PC About with version 0.2.0, GitHub URL and the no-release state |
| `02-android-about.png` | Android About with version 0.2.0, check button and GitHub URL |
| `03-android-no-release.png` | Android check result: no release published yet |

The repository has no published GitHub Release/tag yet, so the end-to-end package download
cannot be exercised until `v0.2.1` is pushed. Version comparison is covered by unit tests
on both platforms; the download/install code is ready for that first release.

## Tests

```text
PC:      72 passed, 0 failed, 1 ignored
Android: 62 passed, 0 failed
```

```text
dist/Lilt-0.2.1-win64/Lilt.exe        12,085,760 bytes  (PE subsystem 2)
dist/Lilt-0.2.1-win64-portable.zip    18,531,896 bytes
base/app/Lilt-0.2.1-debug.apk         22,705,330 bytes
```
