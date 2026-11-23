# MIANCAK v0.3.4-preview.1

This Android 13+ prerelease uses the same package and signing certificate as
MIANCAK v0.3.2. Install it as an update over v0.3.2 to keep app data in place.
Android treats v0.3.2 as a downgrade after installing version code 7. Reinstalling
the older APK over this one will be rejected.

Open **Chinese input setup** to install Wanxiang Base and its grammar model.
Both downloads together are about 417 MB. Installation also needs space for
extracted and compiled data. The APK ships without the Base data and model.
After setup, Chinese input works offline.

This prerelease requires an Android 15 emulator check of the signed v0.3.2
update, including retained settings, learning files, and synthetic legacy YAML.
v0.3.2 remains the latest stable release.
