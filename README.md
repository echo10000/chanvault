## ChanVault v0.5.3 — touch playback scrubbing

- Tap a playing video to reveal a **seekable timeline**, elapsed/total time and **-10 sec / Play-Pause / +10 sec** controls.
- Drag the timeline to jump to any seekable point. Seeking outside buffered content may briefly load more data.
- Controls automatically disappear after about 5.5 seconds, or after you stop dragging; vertical swipes still move between posts.
- Images are not given fake video seek controls; threads and board pin/block options remain on their menus.

## ChanVault v0.5.3 — pin and block threads

- **Pin Thread:** from a thread's **•••** menu or the small **Pin** action in a board catalog. Pins are shown at the top of Discover, with the thread title, board, thumbnail (if available), and a one-tap shortcut.
- **Block Thread:** from the same menu or catalog card (with confirmation). Blocked threads disappear from board catalogs, the Discover board highlights, and Continue Watching; they are removed from pinned threads if previously pinned.
- **Undo safely:** Discover has **Manage blocked threads** to review and unblock; pinned threads have **Unpin**.
- **Local only:** settings are stored in Android app preferences for this installation. Blocking does not block a user or remove a thread from the internet, and does not delete private downloads. Pinning bookmarks the thread; it does not archive posts or videos, so expired/deleted threads might stop opening.

## ChanVault v0.5.2 — fixed downloads and spacing

- Fixed an Android regex interval crash that prevented **Save to vault** and **Download thread** from starting. Source URLs now use strict scheme, host, board, filename, and file-type validation without regex quantifier ranges.
- Added CI smoke tests for normal CDN links and rejected unsafe links. Both debug and signed release builds run these tests.
- Reworked player tools into two rows with balanced tap targets and 12dp horizontal button spacing, keeping them outside the video area.
- Increased spacing and padding for **Save to vault / Posts**, **Download thread**, and the fixed **Browse / Saved Media** navigation bar.
- Adjusted Saved Media's Refresh/Stop and Export/Restore action rows so the buttons don't touch.

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

Download [`app-release.apk`](https://github.com/echo10000/chanvault/releases/download/v0.5.1/app-release.apk) directly from GitHub Releases. This is the permanent **ChanVault** app, not the separate experimental Preview build. Android requires confirmation to install. Previous experimental `dev.chanvault.reader` debug builds have an incompatible certificate, so uninstall the old app **only after** checking for private media you need to preserve. Future releases signed with the same key should update in place.

## ChanVault v0.5.1 — playback and media navigation

- **Fit / Fill / Rotate**: normal video playback preserves aspect ratio; Fill crops rather than stretching, and Rotate can correct footage encoded sideways.
- **No controls over video:** player controls, including Open Original, are below the video frame.
- **Whole-thread media carousel:** expand Browse all media to see every attachment thumbnail, scroll horizontally, and tap to jump. Thumbnails load near the viewport rather than all at once.
- **Progress and sizes:** MP4/WebM show file size (from thread metadata), percent of playable timeline buffered, and percent of file bytes held in the playback cache. Images show transfer percentage when content length is known.
- **Less rebuffering:** a bounded 96 MiB, evicting video cache survives media swipes; previously loaded portions can be reused when going backwards.
- **Gentle look-ahead:** when enabled, ChanVault fetches at most the first 1 MiB of the next video. This is not a full offline download, and it may consume mobile data. Disable with **Preload off** underneath the video.
## ChanVault v0.5

- A collapsible **Up Next** thumbnail tray sits **below**, never over, the playing video.
- Swipe position and thread mode are remembered so you can resume a session.
- Downloads use an Android `dataSync` **foreground service**. Single media and whole-thread queues continue after leaving the screen, with a persistent notification and Stop action. Android, lost connectivity, and battery management may still interrupt transfers.
- The offline vault has **By thread**, **By board**, and **Recent** views, playable tiles, and long-press deletion.
- Saved Media shows background download status, with Refresh and Stop controls.
- **Export vault ZIP** and **Restore ZIP** use Android's system picker. ZIPs are explicit exports outside private storage, so protect them and remove unwanted copies. Restore requires an empty vault.
- Home provides a one-tap **Check for signed updates** control via GitHub Releases.

## Permanent signed releases

Permanent signed releases are published at **[GitHub Releases](https://github.com/echo10000/chanvault/releases/latest)**. v0.5.1 is the current signed release. The key remains outside this public repository in GitHub Actions secrets. Future signed releases must reuse exactly the same key.

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
