# ChanVault

An independent, unofficial **Android imageboard reader with a private offline media vault**. MIT-licensed and open to community contributions.

## Features

- Browse 4chan board catalogs and individual threads (read-only JSON API).
- Save WebM, MP4, GIF, JPG and PNG media directly into Android **app-private** storage.
- Open the **Saved Media** tab for offline playback and deletion, with no files added to Gallery/Photos.
- No storage permission, account, telemetry, or cloud backup. Downloads are limited to 250 MB each.

**Experimental MVP:** This is original source code, not a fork of the proprietary Read Chan app. It has not yet been verified on a physical Android device. The UI is intentionally minimal; bookmarks, posting, thumbnail grids and broad WebM codec support are future work.

## Phone-only APK build

1. Go to **Actions → Build Android APK** in this GitHub repository.
2. Open the latest successful workflow run, or choose **Run workflow**.
3. Under **Artifacts**, download `chanvault-debug-apk` and extract the ZIP on your phone.
4. Install `app-debug.apk`. Android may ask to allow your browser or Files app to install APKs.

This project uses GitHub Actions to install JDK 17, Android SDK 35, Gradle 8.9 and compile a test/debug APK. There is no Gradle wrapper executable in this repository because the workflow installs Gradle.

## Privacy and caveats

Saved files are placed in `getFilesDir()/vault/`, not Android's public Gallery or Downloads folders. Android app sandboxing normally prevents other apps from reading them. They are **not encrypted**. **Uninstalling the app deletes the vault**; there is no export or backup yet. Playback of some WebM codecs depends on your Android device.

Media endpoints are from the official [4chan read-only API](https://github.com/4chan/4chan-API). This is **unofficial**, unaffiliated with Read Chan or 4chan. Use the reader in accordance with the host site's rules. Do not represent it as an official client.

## License

MIT — see [LICENSE](LICENSE).
