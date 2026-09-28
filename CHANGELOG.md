# Changelog

## 0.1.2

- Fixed the Android bridge so the reconstructed NCA filesystem is retained and can be browsed reliably.
- Added NSP and XCI container support for base and optional update packages.
- Added recursive container scanning for NCA and ticket files, including nested XCI layouts.
- Added partition-level reconstruction diagnostics.
- Added base/update Program ID compatibility handling for update-offset IDs.
- Improved package-selection labels and guidance for NSP/XCI.
- Kept output-folder selection on writable Android subfolders rather than assuming a specific protected or writable location.
- Retained the selected file's original relative RomFS path for the rest of the workflow.
- Kept normal single-file extraction unchanged.
- Added **7. Package Modified File (optional)** with **SELECT MODIFIED FILE** and **CREATE OVERRIDE ZIP** actions.
- Added deterministic override packaging at `Mods/romfs/[original path]` using the remembered source path and original filename.
- Added automatic `NX_RomFS_Override.zip` naming with non-conflicting numbered filenames when needed.
- Added post-write ZIP validation to confirm the generated archive can be reopened and contains the expected reconstructed entry.
- Added clear success details showing the Android save location and packaged target, plus surfaced errors when ZIP creation fails.
- Added explicit guidance that NX RomFS Extractor creates the package but does not install, enable, or configure it in third-party software.
- Added prominent backup and external-editing guidance to the README.
- Documented the complete extract → back up → modify externally → package → install/enable workflow while keeping file formats and RomFS paths generic.
- Set Android app version to 0.1.2 / versionCode 3.

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
