# MIANCAK v0.3.4-preview.1

This Android 13+ prerelease uses the same package and signing certificate as
MIANCAK v0.3.2. Install it as an update over v0.3.2 to keep app data in place.

**Before updating**, keep independent copies of your original layout and YAML
files. The app's `rime/` directory is excluded from Android-managed backup and
device transfer. Uninstalling MIANCAK or clearing its storage deletes app-local
settings, imported files, and learned words. An in-place update requires
neither action.

Android rejects an in-place downgrade from this preview (code 7) to v0.3.2
(code 5). To move from the preview to a later stable version while keeping app
data in place, install a stable APK with the same signing key and code 8 or
higher.

Open **Chinese input setup** to install Wanxiang Base and its grammar model.
Both downloads together are about 417 MB. Installation also needs space for
extracted and compiled data. The APK ships without the Base data and model.
After setup, Chinese input works offline.

Publication requires a signed Android 15 emulator update from v0.3.2, with
settings, learning, and synthetic legacy YAML retained, plus an imported
custom YAML setting still active after reboot.
v0.3.2 remains the latest stable release.
