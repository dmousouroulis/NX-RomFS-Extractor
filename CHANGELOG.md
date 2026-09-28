# Changelog

## 0.1.2 (test candidate)

- Fixed the Android bridge so the reconstructed NCA filesystem is retained and can actually be browsed.
- Added NSP and XCI container support for base and optional update packages.
- Added recursive container scanning for NCA and ticket files.
- Added partition-level reconstruction diagnostics.
- Added base/update Program ID compatibility handling for update-offset IDs.
- Updated package-selection labels and guidance for NSP/XCI.
- Kept output-folder selection on writable Android subfolders rather than defaulting to Download.
- Retain the selected file's original relative RomFS path for the rest of the workflow.
- Added optional **Package Modified File** workflow after external editing.
- Modified replacement files are restored to the original filename automatically.
- Override ZIPs now use one deterministic structure: `Mods/romfs/[original path]`.
- Override ZIPs are saved to the same writable folder selected in Step 5.
- The preferred ZIP filename is `NX_RomFS_Override.zip`; if it already exists, a non-conflicting numbered filename is generated.
- The generated ZIP is reopened and validated before success is reported, including verification that the expected reconstructed entry exists.
- ZIP creation failures now surface the actual error to the user.
- Added explicit help text that generated packages are not automatically installed or enabled in other software and that users should keep backups of working files.
- Set the Android app version to 0.1.2 / versionCode 3 for clean on-device upgrade testing.

## 0.1.1

- Clarified base vs update package selection.
- Added version-marker warnings and a swap prompt for obviously reversed selections.
- Added visible RomFS reconstruction progress and clearer browse errors.
- Changed output-folder selection to start at internal storage instead of Download.

## 0.1.0

- Initial public release.
- Base NSP selection.
- Optional update NSP reconstruction.
- Local `prod.keys` handling.
- Browsable RomFS file tree.
- Single-file extraction to a user-selected Android folder.
- Persistent output-folder permission through Android Storage Access Framework.
- Standalone launcher icon and application identity.
