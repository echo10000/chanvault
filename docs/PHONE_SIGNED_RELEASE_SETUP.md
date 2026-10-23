# One-time ChanVault signed release setup (Android phone)

ChanVault's original test APKs used temporary debug signatures. For future in-place updates, publish the first permanent **signed release** using the private signing key generated for this project.

## Prepare signing secrets

1. Download your **ChanVault-Setup.zip** from the ChatGPT conversation and extract it with your phone's Files app. It contains a `.p12` private signing key, setup instructions, and `GitHub-secret-values.txt`.
2. Keep that ZIP secure. Do **not** upload it to this public repository, a GitHub issue, or any public website. Do not share its values in chat.
3. Open [GitHub Actions secrets settings](https://github.com/echo10000/chanvault/settings/secrets/actions) in your browser. If the controls are missing on your phone, enable **Desktop site** in your browser menu.
4. Choose **New repository secret**. Set **Name** to `CHANVAULT_KEYSTORE_B64`. From the local `GitHub-secret-values.txt`, copy the entire long `VALUE` beneath that exact NAME, and paste it into the secret value field. Tap **Add secret**.
5. Add another repository secret named `CHANVAULT_SIGNING_PASSWORD`. Paste its corresponding local `VALUE` (the password) from the same file.

## Publish first signed release

6. Open [Publish signed ChanVault APK](https://github.com/echo10000/chanvault/actions/workflows/release-apk.yml). Under **Run workflow**, use branch `main` and tag `v0.5.0`.
7. When the workflow succeeds, open [ChanVault Releases](https://github.com/echo10000/chanvault/releases/latest) and download the single `app-release.apk` (no ZIP extraction required).
8. This **signed** APK is the stable `ChanVault` app, not `ChanVault Preview`. The old experimental ChanVault uses a different signature and cannot be updated in place. **Only uninstall the old app if you have nothing in its private vault you need to keep.** Preview uses a separate package ID; it can coexist but may later be uninstalled once stable is verified.

## Updating afterward

- Keep the same `.p12` signing key and password for all releases, forever. Save a secure backup. Losing it prevents compatible future updates.
- Update versionName and versionCode in `app/build.gradle` for later releases (e.g. versionName `0.5.1`, versionCode `6`), then publish via the same signed release workflow with matching tag `v0.5.1`.
- On stable ChanVault, use **Check for signed updates** to open the newest release APK. Android will ask you to approve the installation. Updates signed by the SAME key retain your app-private vault.
- Signed updates do not install silently; user confirmation is always required.

## Security and limitations

- Signing certificate SHA-256 pinned in CI: `E9:91:1B:51:FD:E4:32:FF:13:AD:D4:6F:61:07:FB:6C:6F:DF:A4:D0:7E:D9:AA:F6:1B:35:F5:52:F4:95:CA:8D`.
- The GitHub connector cannot write Actions Secrets from this conversation. Adding the two secrets requires the repository owner to do it manually.
- If you have existing private downloads in the older app, back them up and verify the backup **before** uninstalling that app.
