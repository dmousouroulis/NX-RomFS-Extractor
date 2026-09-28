<p align="center">
  <img src="docs/logo.svg" width="132" alt="NX RomFS Extractor logo">
</p>

# NX RomFS Extractor

NX RomFS Extractor is an Android utility for browsing reconstructed RomFS contents, extracting individual files, and packaging a modified replacement back into its remembered RomFS location.

Everything runs locally on the device. The app does not include network permissions or upload selected inputs, extracted files, or generated ZIPs.

## End-to-end workflow

`Select package → Reconstruct RomFS → Browse → Extract → BACK UP → Modify externally → Select modified file → Create Override ZIP → Extract ZIP into target location → Add/enable Mods folder in compatible software`

NX RomFS Extractor handles the extraction and packaging portions of this workflow. Editing the extracted file and installing/enabling the resulting override happen outside the app.

## What it does

- opens a base NSP or XCI directly from Android storage or an SD card;
- optionally opens a matching update NSP or XCI;
- uses a local `prod.keys` file;
- reconstructs the updated Program NCA in place when an update is selected;
- builds a browsable RomFS file tree without dumping the full RomFS;
- lets you select and extract one source file while retaining its original relative RomFS path;
- saves the extracted file directly to a user-selected writable Android folder;
- accepts an externally modified replacement file;
- restores the original filename and remembered RomFS path automatically;
- creates a validated `NX_RomFS_Override.zip` containing `Mods/romfs/[original path]`.

## Complete workflow

### 1. Extract the target file

1. **Select base package** — choose the original/base NSP or XCI.
2. **Select update package (optional)** — choose a matching update NSP or XCI when you want the reconstructed updated RomFS.
3. **Select `prod.keys`** — the file is stored privately inside the app for later use.
4. **Choose the source file inside RomFS** — tap **Browse RomFS**, navigate the reconstructed folders, and select the file you want to extract.
5. **Choose output folder** — select any writable Android subfolder in internal storage or on an SD card. Android may block protected folders or a storage root itself.
6. **Extract file** — the selected source file is written to the chosen output folder under its original filename.

The source file and path are format-specific. NX RomFS Extractor does **not** assume that a title uses XML, `.bundle` files, or any particular directory structure.

A generic example source path might look like:

```text
content/content0/bundles/example.bundle
```

That is only an example of a RomFS path. Other files and formats can live anywhere in the reconstructed tree.

### 2. BACK UP THE ORIGINAL FILE

> **Before modifying anything, make and retain a known-good copy of the original extracted file.**

For example:

```text
example.bundle
example_ORIGINAL_BACKUP.bundle
```

Keep the backup somewhere safe and **do not use the backup as the modified replacement file**. A known-good original gives you something to restore if an edit causes crashes, unexpected behavior, malformed data, or content that no longer loads correctly.

### 3. Modify the extracted file externally

NX RomFS Extractor deliberately does **not** edit the contents of extracted files.

Use an external tool appropriate for the actual format you selected. Depending on that format, this might be:

- a text or XML editor;
- a format-specific editor or utility;
- another tool designed to unpack, edit, and repack that file type;
- an AI-assisted tool that can help inspect or modify the file.

**External editing is at your own risk.** Understand the format you are changing and preserve the original backup. Incorrect values, malformed data, invalid formatting, or incorrect repacking can make the replacement unusable.

A filename extension does not guarantee that a file can be safely edited as plain text. For example, a container or archive format may require a dedicated unpack/repack process even when some of the data stored inside it is text-based.

### 4. Select the modified replacement

After making the desired changes externally, return to NX RomFS Extractor.

Under **7. Package Modified File (optional)**, tap:

**SELECT MODIFIED FILE**

Choose your modified working file. It may have been renamed while you were editing it; that is fine. NX RomFS Extractor uses the **original filename and original RomFS location remembered when the source file was selected** when it builds the package.

### 5. Create the Override ZIP

Tap:

**CREATE OVERRIDE ZIP**

NX RomFS Extractor automatically reconstructs:

```text
Mods/romfs/[original RomFS path]
```

and saves the validated ZIP to the same writable output folder selected in Step 5 of the app.

Generic example:

Original source:

```text
content/content0/bundles/example.bundle
```

Generated ZIP:

```text
NX_RomFS_Override.zip
└── Mods/
    └── romfs/
        └── content/
            └── content0/
                └── bundles/
                    └── example.bundle
```

Equivalent packaged target:

```text
Mods/romfs/content/content0/bundles/example.bundle
```

The app reopens the ZIP after writing it and verifies that the expected reconstructed entry exists before reporting success. If `NX_RomFS_Override.zip` already exists in the selected output folder, the app generates a non-conflicting numbered filename instead of silently overwriting it.

### 6. Install/use the resulting override

Extract `NX_RomFS_Override.zip`, then place or merge the resulting **`Mods`** directory into the target title's directory/location used by your compatible emulator or LayeredFS implementation.

Conceptually, the result should look like:

```text
Target Title/
└── Mods/
    └── romfs/
        └── [original RomFS path]
```

In software that supports per-title LayeredFS overrides, the extracted `Mods` directory can typically be selected, installed, or enabled through that software's per-title **Mods**, **Add-ons**, or **LayeredFS** interface. Exact installation steps and directory locations vary between compatible software.

> **NX RomFS Extractor creates the override package; it does not automatically install, enable, or configure the resulting modification in third-party software.**

Keep a backup of the original or previously working file before replacing or enabling any modified content.

## Current support

### v0.1.2

- Base package: NSP or XCI
- Optional update package: NSP or XCI
- Android Storage Access Framework file and folder selection
- RomFS folder browser
- In-place base + update reconstruction
- Single-file extraction
- Original relative RomFS path retention
- Persistent Android output-folder selection
- On-device `prod.keys` handling
- Modified replacement selection
- Deterministic override ZIP creation at `Mods/romfs/[original path]`
- Original filename restoration inside the override package
- ZIP validation before success is reported
- Non-conflicting override ZIP filenames
- Native partition diagnostics for failed RomFS reconstruction

## Output folder selection

Choose a writable subfolder in internal storage or on an SD card. Android may prevent apps from selecting protected locations or a storage root itself. The folder picker starts at internal storage rather than assuming a particular folder is writable on every device.

## Build

The native parser sources and supporting libraries are fetched at build time so this repository stays focused on the Android application and its extraction layer.

GitHub Actions builds the APK automatically. A local build requires Android SDK/NDK, CMake, JDK 17, Gradle, Git, and Python 3.

## Credits

NX RomFS Extractor uses work from:

- [**nxinfo**](https://github.com/jayl-dev/nxinfo) by **jayl-dev** — Android file-selection and integration foundation.
- [**NSTool**](https://github.com/jakcron/nstool) by **jakcron** — native package, NCA, filesystem, and RomFS parsing foundation.
- Supporting libraries maintained by jakcron, including `libmbedtls`, `libfmt`, `libtoolchain`, `liblz4`, and `libpietendo`.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attribution and license information.

## Privacy

The application performs its work locally on the Android device. It does not include network permissions and does not upload selected inputs or generated output.

## License

Project-specific code is provided under the MIT License. Third-party components retain their respective licenses and notices.
