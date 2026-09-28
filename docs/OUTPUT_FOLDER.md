# Output folder selection

NX RomFS Extractor uses Android's Storage Access Framework for extracted files and generated override ZIPs.

When choosing an output folder, select a writable subfolder in internal storage or on an SD card. Android may prevent apps from selecting protected locations or a storage root itself. The picker starts at internal storage rather than assuming a particular folder is writable on every device.

The same selected output folder is used for normal file extraction and for `NX_RomFS_Override.zip` created by **7. Package Modified File (optional)**.
