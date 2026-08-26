# NDTiff-FIJI

A [Fiji](https://fiji.sc)/ImageJ2 plugin (built on the [SCIFIO][scifio]
framework) for opening [Micro-Manager](https://micro-manager.org) NDTiff
datasets directly in Fiji - drag-and-drop an NDTiff dataset onto the main
Fiji window and it opens as a correctly-dimensioned hyperstack, the same way
Micro-Manager's own viewer would open it.

## Features

- Drag-and-drop an NDTiff dataset folder - or a single one of its
  `*_NDTiffStack.tif` files - onto the main Fiji window. `File > Open...`
  works the same way.
- Opens as a properly dimensioned hyperstack (Z / Channel / Time / Position
  axes, as recorded in the dataset).
- For datasets larger than 2GB, prompts to open as a virtual stack (loading
  planes from disk on demand instead of reading the whole dataset into
  memory), matching classic ImageJ's own large-TIFF behavior.
- Shows read progress in Fiji's status bar while a dataset loads.

### Limitations

- Read-only - this plugin cannot create or write NDTiff datasets.
- RGB NDTiff datasets are not currently supported and will fail to open
  with an explicit error (this lab's data is all single-channel
  fluorescence microscopy, so this path has never been implemented/tested).

## Installation

1. Download `NDTiff-FIJI-<version>.jar` from the [Releases][releases] page.
2. Copy it into your Fiji installation's `jars` folder.
   - **Windows / Linux**: this is the `jars` folder directly inside your
     `Fiji.app` folder.
   - **macOS**: if Fiji shows up as a single `Fiji.app` application icon,
     right-click it, choose **Show Package Contents**, and look for `jars`
     inside. (On some macOS installs, `jars` instead sits as a sibling
     folder next to `Fiji.app`, rather than inside it - either way, it's
     the same `jars` folder Fiji itself uses.)
3. Restart Fiji if it was already running - it only scans the `jars` folder
   at startup.

No other setup is required: this jar already bundles the two non-Fiji
libraries it depends on (Micro-Manager's `NDTiffStorage`/`MMCoreJ`), so it's
a single-file install.

## Usage

Just drag an NDTiff dataset folder (or one of its `*_NDTiffStack.tif` files)
onto the main Fiji window, or use `File > Open...` and select it. If the
dataset is over 2GB, you'll be asked whether to open it as a virtual stack.

## Building from source

This project builds with Maven (developed against Eclipse + m2e - open it
via `File > Import > Existing Maven Projects`, then use
`Run As > Maven build...` with goal `package`):

```
mvn package
```

This produces a single self-contained `target/NDTiff-FIJI-<version>.jar`
(SCIFIO/SciJava/ImageJ dependencies are left out, since every Fiji install
already has compatible versions of those; only the non-Fiji dependencies are
bundled in) - this is the same jar published in each
[release](../../releases), and the one to copy into `jars/` per the
Installation instructions above.

## License

Released under the [MIT License][mit] - see [LICENSE.md](LICENSE.md).

## Author

Daniel Dickinson ([Dickinson Lab][lab], University of Texas at Austin)

[scifio]: https://imagej.net/formats/scifio
[mit]: https://opensource.org/license/mit/
[lab]: https://www.utdickinsonlab.org
[releases]: https://github.com/dickinson-lab/NDTiff-FIJI/releases
