# SpoolTag Writer

<img src="docs/icon.png" width="128" alt="Turquoise filament spool with NFC waves">

An offline Android app that writes filament JSON to NFC tags as a **single NDEF
MIME record**, `application/json`, for OpenSpool-compatible integrations.

Designed for **Android 16**, with support for Android 8.0 (API 26) and newer.
An NFC-capable phone and writable NDEF tags are required; **NTAG215 or NTAG216**
are recommended.

## Download and install

Download the signed `SpoolTag-Writer-<version>-release.apk` from
[GitHub Releases](https://github.com/meowelectronics/SpoolTag-Writer/releases).
Allow installation from your browser/file manager if Android prompts you.

**Moving from the early debug APK:** the release uses a dedicated signing key.
Android cannot install it over the debug-signed app. Uninstall the debug version
once, install the release, then reimport your JSON/ZIP bundle. Uninstalling clears
the app's saved library and local edits, but does not change physical tags.
Subsequent releases signed with the same release key can update normally.

## Features

- Import individual UTF-8 JSON files or ZIP bundles.
- Save the library locally; search by filename, manufacturer, material or color.
- Preview compact JSON and the full NDEF message size.
- Edit the manufacturer stored in a payload.
- Read tags and write exactly one MIME record, not an NDEF text record.
- Confirm replacement of existing records or formatting of a blank tag.
- Check capacity and read-only state; verify the written payload.
- Adaptive filament-spool/NFC launcher icon, including Android themed icons.
- No internet permission, analytics, account or broad storage permission.

## Write a filament tag

1. Enable NFC and open SpoolTag Writer.
2. Select **Open ZIP** or **Choose JSON**, then select a filament.
3. Use **JSON** to inspect the payload if desired.
4. Tap **Write tag** and hold the tag against the phone's NFC antenna.
5. If the tag already contains data, confirm replacement, remove it completely,
   then retap the **same tag**. All previous NDEF records will be replaced.
6. Wait for **VERIFIED**. Keep the tag still until the operation finishes.

### A write returned, but immediate verification failed

The write may have succeeded. Keep the app open, remove the tag completely and
retap the **same tag**, without pressing Write again. The next scan only verifies
the contents; it does not rewrite them. A newly formatted tag also needs a retap.
An unverified write is never reported as a verified success.

Reader mode captures tags while the app is in the foreground so Android does not
launch another NFC app. Switching away while waiting to scan cancels the scan.
Presence checks are not periodic rewrites or resets of an active connection.

## Update the library

- **Open ZIP** merges by filename, replacing matching files.
- **Refresh ZIP** asks for confirmation, then replaces the entire library with
  the selected bundle and clears the search filter. Use this for renamed or
  corrected presets; local manufacturer edits are replaced.
- Invalid/unreadable imports leave the existing saved library unchanged.

Refresh reads a local ZIP; it does not download updates. Library changes never
modify physical tags automatically: rewrite affected tags.

## Payload format and compatibility

An example payload (use the actual manufacturer's temperatures for your spool):

```json
{
  "protocol": "openspool",
  "version": "1.0",
  "type": "PLA",
  "color_hex": "00A6A6",
  "brand": "Aurapol",
  "min_temp": "210",
  "max_temp": "230"
}
```

OpenSpool fields are strings. The app validates OpenSpool 1.0 fields when
`"protocol": "openspool"` is present, and compacts JSON without changing values.
Other JSON objects can also be written as MIME payloads.

Limits: 100 library files, 64 KiB per JSON, 256 ZIP entries and 2 MiB total
expanded ZIP data, including ignored entries. ZIP files are never extracted to
filesystem paths; non-JSON entries are ignored.

This is **not** a Creality CFS/SpoolFID encoder, a proprietary RFID key writer,
or a Spoolman spool-link generator. A tag does not install slicer presets.
Printer recognition depends on its firmware/integration. NFC support alone
does not guarantee MIFARE Classic support. Unsupported/read-only tags are
rejected; the app never locks tags. A single RGB value cannot describe glitter,
marble speckles, silk or transparency accurately.

## Build and test

Requirements: **JDK 21**, Android SDK platform 36, Build Tools 35.0.0, and accepted
Android SDK licenses. Set `ANDROID_HOME` or put `sdk.dir` in an untracked
`local.properties`. The wrapper uses Gradle 8.13.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:exportDebugApk
```

Debug APKs go to `app\build\outputs\installable`. Android 16 Robolectric tests
require JDK 21. Tests cover JSON/ZIP validation, merge/refresh behavior, MIME wire
encoding, exact payload comparison and reader-mode lifecycle behavior.

### Signed release build

Create and securely back up a private Android release keystore outside this
repository. **Never commit the keystore or passwords.** Losing the key prevents
updates to installations signed with it.

Set these environment variables in the same shell that runs Gradle:

```powershell
$env:SPOOLTAG_KEYSTORE = "C:\private\spooltag-release.p12"
$env:SPOOLTAG_KEY_ALIAS = "spooltag"
# Set SPOOLTAG_STORE_PASSWORD and SPOOLTAG_KEY_PASSWORD securely in your shell.
.\gradlew.bat :app:lintRelease :app:exportReleaseApk
```

The export task requires all four variables and creates
`app\build\outputs\installable\SpoolTag-Writer-<version>-release.apk`.
Release builds are not debuggable. Verify signatures with Android SDK
`apksigner verify --verbose --print-certs <apk>`. Publish the APK and its SHA-256
checksum with a version tag; never publish signing material.

License and third-party notices are bundled at `assets/licenses/` in the APK.

### Physical acceptance checks

- Import a bundle, restart, and confirm the library persists.
- Write/read a blank NTAG215/216 and inspect it with an independent NFC reader:
  exactly one `application/json` record with the selected compact JSON.
- Check overwrite cancellation, wrong-tag protection, read-only/undersized tags,
  connection loss, formatting and remove/retap verification.
- Check foreground capture, switching apps, rotation and large font sizes.

Physical NFC behavior cannot be validated by an emulator or build alone.

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for third-party attribution.
Manufacturer names do not imply endorsement.

## References

- [OpenSpool RFID format](https://github.com/spuder/OpenSpool/blob/main/docs/rfid.md)
- [Android NFC documentation](https://developer.android.com/develop/connectivity/nfc/nfc)
