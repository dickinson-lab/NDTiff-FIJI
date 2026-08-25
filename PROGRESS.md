# Progress notes

Working notes on the state of this project, for picking the work back up on
another machine. This is a scratch record, not user-facing documentation.

## Goal

Port Micro-Manager's NDTiff-opening/display logic into a SciJava/SCIFIO-based
ImageJ/Fiji plugin: drag-and-drop an NDTiff file or folder onto the main
ImageJ window, open it as a correctly-dimensioned ImagePlus/hyperstack, and
prompt for virtual-stack mode if the dataset is over 2GB.

Plan (see chat history for full reasoning):
- Fork of `scifio/example-scifio-format` as the project skeleton, since a
  registered SCIFIO `Format` is picked up automatically by Fiji's drag-and-drop
  fallback path (unrecognized files/folders fall through to the SCIFIO IO
  service).
- Depend on `io.scif:scifio` for the Format/Checker/Parser/Reader/Writer
  skeleton, and (not yet added) `NDStorage`
  (github.com/micro-manager/NDStorage, `NDTiffStorage`/`NDTiffAPI`) for the
  actual NDTiff index/pixel reading — pure Java, no ImageJ dependency.
- Model the virtual-stack ("open as virtual stack?" size-based prompt) on
  classic ImageJ1's own large-TIFF prompt, and the lazy-plane-loading pattern
  on Micro-Manager's `MMVirtualStack` (`ij.VirtualStack` subclass) and
  `DefaultImageJConverter`/`ImageJConverter` for dimension/metadata mapping.
- Bio-Formats' `MicromanagerReader` does NOT support NDTiff (only the old
  `metadata.txt`-based MM format) — not usable as a base.

## Current state

`NDTiffFormat.java` / `NDTiffFormatTest.java` (renamed from the tutorial's
`FictionalImageFormat`) are still the **placeholder tutorial logic** — a
fictional ".fif" format, not real NDTiff parsing yet. All 6 SCIFIO component
types (Metadata/Checker/Parser/Reader/Writer/Translator) compile and their
unit tests pass against current SCIFIO. This is the clean starting point for
writing the real NDTiff-reading logic.

## Fixes applied to get here (pom.xml / build)

- `maven.imagej.net` is dead (301-redirects to `maven.scijava.org`). The pom's
  repository entry now points at `https://maven.scijava.org/content/groups/public`
  (must be `https://`, not `http://` — Maven 3.8+ silently blocks insecure
  repos with a fake 500-returning mirror, which is what the original error
  actually was).
- `pom-scijava` parent bumped `14.0.0` → `40.0.0` (2017-era → current), which
  moves the managed `scifio` version `0.31.1` → `0.46.0`.
- `io.scif:scifio-jai-imageio` (a required/non-optional transitive dep of
  `scifio`, for JAI-based codecs like JPEG2000) is excluded in pom.xml — it's
  a permanently broken artifact on the SciJava Nexus (licensing/hosting
  issues, documented on the Image.sc forum). **Do not re-add it** — it has
  nothing to do with reading NDTiff.
- After any pom.xml edit made outside Eclipse's own editor: **F5 (refresh)
  the project first**, then Maven → Update Project → Force Update of
  Snapshots/Releases. Eclipse won't otherwise notice the on-disk change.

## SCIFIO 0.31.1 → 0.46.0 API changes (already applied in the port)

- `io.scif.io.RandomAccessInputStream`/`RandomAccessOutputStream` →
  `org.scijava.io.handle.DataHandle<Location>` (single type for read+write).
- Plane bounds went from a `long[] planeMin, long[] planeMax` pair (where
  planeMax held *sizes*, not inclusive max indices) to a single
  `net.imglib2.Interval`. Old `(min, size)` pairs map to
  `net.imglib2.util.Intervals.createMinSize(min..., size...)`.
- `getStream()` → `getHandle()` on Reader/Writer.
- `ByteArrayPlane` constructors dropped the `Context` param: now
  `(ImageMetadata, Interval)` or no-arg.
- For tests: build a `DataHandle<Location>` from a plain `byte[]` via
  `context.getService(DataHandleService.class).create(new BytesLocation(bytes))`
  — **not** `new BytesHandle(new BytesLocation(...))` directly, since
  `BytesHandle` implements `DataHandle<BytesLocation>`, which Java generics
  won't let you assign to a `DataHandle<Location>`-typed variable.
- `io.scif.formats.FakeFormat` (used by the old
  `testTranslateImageMetadata` as a convenient synthetic-metadata source) no
  longer exists anywhere in current SCIFIO. Test was rewritten to build an
  `ImageMetadata` by hand (`io.scif.DefaultImageMetadata`) instead of
  depending on it.

Verified outside Eclipse by compiling + running the JUnit suite directly
against the jars in `~/.m2/repository` (10/10 tests pass). Note: running
`javac` standalone without the SciJava annotation processor writing
`META-INF/json/org.scijava.plugin.Plugin` will make `FormatService` unable
to find the `@Plugin`-annotated `Format`, which breaks anything that calls
`Reader.setSource(DataHandle)` (it internally re-parses via
`getFormat().createParser()`) — this is a quirk of ad hoc `javac` runs, not a
real bug; Eclipse/Maven's normal build handles it fine, which is also what
`scijava-maven-plugin:eclipse-helper` (bound to `process-classes`) is for.

## Next step

Start replacing the placeholder Metadata/Checker/Parser/Reader (and skip the
Writer — NDTiff is read-only for our purposes) with real logic backed by
`NDTiffStorage`/`NDTiffAPI` from NDStorage.
