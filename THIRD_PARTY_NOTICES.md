# Third-party notices

NX RomFS Extractor includes or builds against third-party work. Those components retain their original licenses and attribution.

## nxinfo

Android file-selection and integration concepts were derived in part from **nxinfo** by **jayl-dev**.

Project: `https://github.com/jayl-dev/nxinfo`

The upstream author explicitly permits reuse and modification in the project README. NX RomFS Extractor preserves attribution here and in the main README.

## NSTool

Native package, NCA, filesystem, and RomFS parsing is based on **NSTool** by **jakcron**.

Project: `https://github.com/jakcron/nstool`

NSTool is distributed under the MIT License. Its source is fetched at build time at a pinned revision rather than vendored into this repository.

## Supporting libraries

The build also fetches pinned revisions of the following supporting libraries maintained by jakcron:

- `libmbedtls`
- `libfmt`
- `libtoolchain`
- `liblz4`
- `libpietendo`

Each library remains subject to its own upstream license and notices.

## License preservation

Third-party license texts and notices must remain intact when redistributing the corresponding third-party source or binaries. The MIT License in this repository applies to project-specific NX RomFS Extractor code and does not replace third-party licenses.
