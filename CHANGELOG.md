# Changelog

## 0.1.1

- Fixed update-package RomFS reconstruction when the update Program NCA uses a different Program ID from the base package.
- Applied the same fix to extraction from reconstructed updated RomFS content.
- Added clearer base/update package guidance and filename-version warnings.
- Added automatic swap guidance when filenames strongly suggest the base and update were selected in reverse.
- Added visible RomFS reconstruction progress and clearer error dialogs.
- Output-folder browsing now starts from internal storage instead of Download.
- Clarified that users should choose a writable subfolder because Android may restrict protected folders and storage roots.

## 0.1.0

- Initial public release.
- Base NSP selection.
- Optional update NSP reconstruction.
- Local `prod.keys` handling.
- Browsable RomFS file tree.
- Single-file extraction to a user-selected Android folder.
- Persistent output-folder permission through Android Storage Access Framework.
- Standalone launcher icon and application identity.
