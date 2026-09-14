<p align="center">
  <img src="docs/logo.svg" width="132" alt="NX RomFS Extractor logo">
</p>

# NX RomFS Extractor

NX RomFS Extractor is an Android utility for browsing reconstructed RomFS contents and extracting individual files directly to a folder on the device.

Everything runs locally. The app does not upload selected packages, keys, or extracted files.

## What it does

- opens a base NSP directly from Android storage or an SD card;
- optionally opens an update NSP;
- uses a local `prod.keys` file;
- reconstructs the updated Program NCA in place when an update is selected;
- builds a browsable RomFS file tree without dumping the full RomFS;
- lets you select one source file;
- saves the extracted file directly to a user-selected Android folder while keeping its original filename.

## Basic workflow

1. **Select base package** — choose the base NSP.
2. **Select update package (optional)** — choose an update NSP if you want the reconstructed updated RomFS.
3. **Select `prod.keys`** — the file is stored privately inside the app for later use.
4. **Choose the source file inside RomFS** — tap **Browse RomFS**, navigate the internal folders, and select the file you want. For example, a path can look like:

   ```text
   content/content0/bundles/xml.bundle
   ```

5. **Choose output folder** — select an Android folder such as **Internal storage → Download**. The app remembers the folder permission while Android keeps it available.
6. **Extract file** — the selected source file is written directly to that output folder under its original filename.

Step 4 selects the **source file inside RomFS**. Step 5 selects the **destination folder on Android**.

## Current support

### v0.1.0

- Base package: NSP
- Optional update package: NSP
- Android Storage Access Framework file and folder selection
- RomFS folder browser
- In-place base + update reconstruction
- Single-file extraction
- Persistent Android output-folder selection
- On-device `prod.keys` handling

Planned improvements include additional container support, search/filtering inside large RomFS trees, and multi-file extraction.

## Build

The native parser sources and supporting libraries are fetched at build time so this repository stays focused on the Android application and its extraction layer.

GitHub Actions builds the APK automatically. A local build requires Android SDK/NDK, CMake, JDK 17, Gradle, Git, and Python 3.

## Credits

NX RomFS Extractor uses work from:

- **nxinfo** by **jayl-dev** — Android file-selection and integration foundation.
- **NSTool** by **jakcron** — native package, NCA, filesystem, and RomFS parsing foundation.
- Supporting libraries maintained by jakcron, including `libmbedtls`, `libfmt`, `libtoolchain`, `liblz4`, and `libpietendo`.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attribution and license information.

## Privacy

The application performs its work locally on the Android device. It does not include network permissions and does not upload selected files, keys, or extracted output.

## License

Project-specific code is provided under the MIT License. Third-party components retain their respective licenses and notices.