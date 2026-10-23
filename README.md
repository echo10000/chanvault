# ChanVault

An independent, unofficial **Android imageboard reader with a private offline media vault**. MIT-licensed and open to community contributions.

## Features

- Browse 4chan catalogs in touch-friendly thumbnail cards (read-only JSON API).
- Discover active, non-pinned threads directly from the Quick Boards home screen, with preview thumbnails and reply counts.
- Heart boards to add them to persistent on-device Favorites; recently visited boards can appear in Discover.
- Get a compact **Up Next** strip in each swipe feed with three tappable thumbnail previews.
- See an animated loading spinner for media, clear retry states for failed previews, and redesigned controls with real vector icons.
- Custom ChanVault launcher icon; a padded Favorites section on Home.
- Swipe up and down through a thread's media in a Shorts-style feed, or switch to all posts.
- Stream WebM/MP4 previews **before** deciding whether to save; fallback to the original in a browser when your device lacks the codec.
- Batch-download supported attachments from a thread with confirmation, progress, duplicate skipping, and cancellation between files.
- Save WebM, MP4, GIF, JPG and PNG media directly into Android **app-private** storage.
- Open the **Saved Media** tab for offline playback and deletion, with no files added to Gallery/Photos.
- No storage permission, account, telemetry, or cloud backup. Downloads are limited to 250 MB each.

**Experimental v0.5:** This is original source code, not a fork of the proprietary Read Chan app. It has not yet been verified on a physical Android device. The UI remains experimental; bookmarks, posting, richer saved-media thumbnails and broader WebM codec support are future work.

## Install the official signed APK

Download [`app-release.apk`](https://github.com/echo10000/chanvault/releases/download/v0.5.0/app-release.apk) directly from GitHub Releases. This is the permanent **ChanVault** app, not the separate experimental Preview build. Android requires confirmation to install. Previous experimental `dev.chanvault.reader` debug builds have an incompatible certificate, so uninstall the old app **only after** checking for private media you need to preserve. Future releases signed with the same key should update in place.

## ChanVault v0.5

- A collapsible **Up Next** thumbnail tray sits **below**, never over, the playing video.
- Swipe position and thread mode are remembered so you can resume a session.
- Downloads use an Android `dataSync` **foreground service**. Single media and whole-thread queues continue after leaving the screen, with a persistent notification and Stop action. Android, lost connectivity, and battery management may still interrupt transfers.
- The offline vault has **By thread**, **By board**, and **Recent** views, playable tiles, and long-press deletion.
- Saved Media shows background download status, with Refresh and Stop controls.
- **Export vault ZIP** and **Restore ZIP** use Android's system picker. ZIPs are explicit exports outside private storage, so protect them and remove unwanted copies. Restore requires an empty vault.
- Home provides a one-tap **Check for signed updates** control via GitHub Releases.

## Permanent signed releases

The first permanent release **[v0.5.0](https://github.com/echo10000/chanvault/releases/tag/v0.5.0)** is published and signed. The key remains outside this public repository in GitHub Actions secrets. Future signed releases must reuse exactly the same key.

Create or securely generate a PKCS#12 keystore with alias `chanvault`. Back up that keystore and its password **outside this public repository**. In GitHub Settings -> Secrets and variables -> Actions, configure:

- `CHANVAULT_KEYSTORE_B64` — full base64 of the private keystore file.
- `CHANVAULT_SIGNING_PASSWORD` — matching keystore and key password.

Then Actions -> **Publish signed ChanVault APK** -> Run workflow, entering a version tag such as `v0.5.0`. The workflow verifies the signature and publishes the APK to GitHub Releases. Future signed versions MUST use the same private key for in-place updates.

**Critical existing-install warning:** Earlier debug APKs are signed using temporary GitHub runner keys. A permanently signed release cannot update them in place, and uninstalling erases their app-private vault. Version 0.4 did not include ZIP export. **Do not uninstall an existing debug APK while it holds files you need.** The old debuggable app's private files may be recoverable through USB debugging from a trusted computer, or you can re-download the originals after migration. The ZIP backup feature helps preserve data from v0.5 onward, but only once you have installed v0.5.
## Safe testing after Android app conflicts

CI debug builds now use a **separate package ID**, `dev.chanvault.reader.preview`, and appear as **ChanVault Preview**. This avoids an installation conflict with the original `dev.chanvault.reader`, so its saved media remains intact. The Preview version has its own empty vault; it will not inherit saved media from the original app.

**Do not uninstall the old app** if it contains saved files. Debug APKs produced on different GitHub runners may still conflict with earlier Preview APKs due to ephemeral signing keys. Proper in-place updates need the permanently signed GitHub Release workflow and a consistent, securely stored signing key.

To migrate the original private vault safely, back it up using trusted ADB `run-as` access on the installed debuggable build. Do not uninstall until that backup has been independently verified.

## Phone-only APK build

1. Go to **Actions → Build Android APK** in this GitHub repository.
2. Open the latest successful workflow run, or choose **Run workflow**.
3. Under **Artifacts**, download `chanvault-debug-apk` and extract the ZIP on your phone.
4. Install `app-debug.apk`. Android may ask to allow your browser or Files app to install APKs.

This project uses GitHub Actions to install JDK 17, Android SDK 35, Gradle 8.9 and compile a test/debug APK. There is no Gradle wrapper executable in this repository because the workflow installs Gradle.

## Privacy and caveats

Saved files are placed in `getFilesDir()/vault/`, not Android's public Gallery or Downloads folders. Android app sandboxing normally prevents other apps from reading them. They are **not encrypted**. **Uninstalling the app deletes the vault**; version 0.5 adds opt-in ZIP export/restore, which is not available on older installations. Playback of some WebM codecs depends on your Android device.

Media endpoints are from the official [4chan read-only API](https://github.com/4chan/4chan-API). This is **unofficial**, unaffiliated with Read Chan or 4chan. Use the reader in accordance with the host site's rules. Do not represent it as an official client.

## License

MIT — see [LICENSE](LICENSE).
