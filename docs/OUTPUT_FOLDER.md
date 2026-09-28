# Output folder selection

The app uses Android's Storage Access Framework for output.

When choosing an output folder, select a writable subfolder in internal storage or on an SD card. Android may prevent apps from selecting protected locations or a storage root itself. The app starts the picker at internal storage rather than assuming `Download` is writable on every device.
