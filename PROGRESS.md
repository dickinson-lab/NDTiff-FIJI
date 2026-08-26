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

`NDTiffFormat.java` now contains real NDTiff-reading logic (Metadata/
Checker/Parser/Reader — no Writer/Translator, NDTiff is read-only for our
purposes). **It builds clean (`mvn package` via Eclipse's Run As → Maven
build...), the JUnit suite passes, and it has been confirmed working in a
real Fiji install** (`/Applications/Fiji/`, jars copied into its `jars/`
folder — see "Installing into a real Fiji" below) against a real
Micro-Manager-acquired dataset, including drag-and-drop. The debugging that
got it there surfaced several real, non-obvious bugs/gaps - both in this
plugin's own code and in the third-party `NDTiffStorage` library - recorded
below so they don't have to be rediscovered.

Design decisions made this session:

- **Dependency**: added `org.micro-manager.ndtiffstorage:NDTiffStorage:2.18.4`
  and `org.micro-manager.mmcorej:MMCoreJ:10.1.1.0` (both on Maven Central) to
  pom.xml. `NDTiffStorage` depends on `MMCoreJ` only for the plain-data
  `TaggedImage`/`JSONObject` classes it uses as its pixel+metadata carrier —
  verified by reading their source (`mmCoreAndDevices` repo) that neither
  touches JNI/native code, so this is an ordinary Maven dependency, not a
  native-binding risk.
- **Axis convention**: fixed, not generic. Only axis keys `"z"`, `"channel"`,
  `"time"`, `"position"` are recognized (matching
  `org.micromanager.acqj.main.AcqEngMetadata.{Z,CHANNEL,TIME,POSITION}_AXIS`
  — i.e. what Micro-Manager's own acquisition engine actually writes). Any
  other axis key found in the dataset's `getAxesSet()` makes the Parser throw
  a `FormatException` naming the bad key, rather than silently dropping data.
  Non-planar axis order (fastest-varying first, for
  `FormatTools.rasterToPosition`) is `[z, channel, time, position]`, filtered
  down to whichever of those are actually present in the dataset.
- **Directory-as-format**: an NDTiff dataset is a folder, not a single file,
  which doesn't fit SCIFIO's usual single-byte-stream model. Solved by:
  `Checker` sets `suffixNecessary()`/`suffixSufficient()` both `false` and
  overrides `isFormat(DataHandle<Location>)` to call `handle.get()`, cast to
  `FileLocation`, and check for `NDTiff.index` (or the legacy
  `Full resolution/NDTiff.index`) — it never touches the handle's byte-stream
  methods. `Parser.typedParse` does the same `handle.get()` → `FileLocation`
  → `getFile()` dance to get a `java.io.File` to hand to
  `new NDTiffStorage(dir)`. This is a real, sanctioned SCIFIO idiom
  (`AbstractParser.parse` has an explicit "Location-only" proxy-handle path
  for exactly this case), confirmed by reading `AbstractChecker`/
  `AbstractParser` source directly, not guessed.
- **Opening/dragging a single stack `.tif` file, not just the dataset
  folder**: a user may select or drag-and-drop one of the dataset's own
  `*_NDTiffStack.tif` files instead of the enclosing folder. Detected by
  `NDTiffFormat.ndtiffDatasetDirectoryForStackFile(File)`: the file's name
  must end in `"NDTiffStack.tif"` (the exact suffix Micro-Manager's
  `ResolutionLevel.createBaseFilename` always produces - verified by reading
  that method's source) and an `NDTiff.index` file must sit next to it in
  the same folder; if both hold, that folder is treated exactly as if it
  had been the thing opened/dropped. If either check fails (e.g. a
  coincidentally-named but unrelated `.tif`, or one with no `NDTiff.index`
  next to it), it's rejected and falls through to Fiji's normal plain-TIFF
  handling instead of being misidentified. This check is shared by all
  three gatekeepers that need to agree on what counts as "an NDTiff
  dataset" - `Checker.isFormat`, `Parser.typedParse` (which resolves the
  file back to its folder before doing anything else), and
  `NDTiffIOPlugin.supportsOpen` (the *eager* drag-and-drop gate, which runs
  before SCIFIO's own `Checker` ever gets a chance - missing this one would
  mean a dropped stack file falls through to `ij.plugin.FolderOpener`/plain
  TIFF handling before this format is ever consulted, same class of bug the
  "Drag-and-drop wiring" note below already covers for folders).
- **Drag-and-drop wiring**: added `NDTiffIOPlugin.java`, a separate
  `@Plugin(type = IOPlugin.class, priority = Priority.HIGH, attrs =
  {@Attr(name = "eager")})`. This matters because Fiji's legacy
  drag-and-drop handler (`net.imagej.legacy.plugin.DefaultLegacyOpener`)
  only routes through SCIFIO's `FormatService` at all if "Use SCIFIO when
  opening files (BETA!)" is enabled in Edit > Options > ImageJ2 — which is
  **off by default** — unless an `IOPlugin` is tagged `eager`, in which case
  it gets tried regardless of that setting. Without this class, dropping an
  NDTiff folder onto Fiji would silently fall through to
  `ij.plugin.FolderOpener` (plain image-sequence loading) instead of this
  plugin, on a stock install.
- **RGB pixel type**: NDTiff's 8-bit RGB pixel type is deliberately
  rejected with a clear `FormatException` rather than implemented, because
  correctly reproducing `NDTiffReader`'s BGRA-ish byte packing in SCIFIO's
  planar-channel-axis model needs real RGB test data to verify against, and
  this lab's data is fluorescence microscopy (mono 8/10/12/14/16-bit per
  channel), where this path is never hit. All mono bit depths are fully
  supported (10/12/14-bit are stored 16-bit-wide on disk and are all mapped
  to SCIFIO's UINT16 pixel type, with the real bit depth kept in
  `ImageMetadata.bitsPerPixel` for contrast-scaling).
- **Missing planes**: if a requested plane's axes combination isn't in the
  dataset (e.g. an acquisition aborted partway through a channel/time/z
  sequence), `Reader.openPlane` returns a blank (all-zero) image instead of
  throwing — matches Micro-Manager's own viewer behavior for in-progress or
  aborted acquisitions.
- **Tests**: `NDTiffFormatTest.java` was fully rewritten (the old one only
  tested the tutorial's fictional format). Rather than shipping a binary
  NDTiff fixture, it builds tiny real datasets on disk using
  `NDTiffStorage`'s own write-side API
  (`new NDTiffStorage(dir, name, summaryMD, 0, 0, false, null, queueSize,
  null, /*createDir=*/false)` + `putImage(...)` + `finishedWriting()` +
  `closeAndWait()`), then reads them back through the real
  Checker/Parser/Reader. Covers: Checker accept/reject, parsed
  `ImageMetadata` axes/lengths, pixel round-trip through `openPlane`, the
  missing-plane-returns-blank fallback, and the unrecognized-axis
  `FormatException`.
- **Progress bar while loading**: `ImgOpener` never reports read progress on
  its own - that per-plane loop has no `StatusService` calls anywhere in it,
  regardless of format, so opening any many-plane dataset is silent unless
  the `Reader` itself reports progress. `Reader` now has a `StatusService`
  `@Parameter` and calls `showStatus(planeIndex + 1, totalPlanes, ...)` once
  per `openPlane` call - `StatusService` is what `imagej-legacy` bridges to
  the classic IJ1 status/progress bar at the bottom of the main Fiji window,
  so this isn't a workaround, it's the normal mechanism, just something
  every format has to opt into individually.
- **>2GB virtual-stack-mode prompt**: implemented in `Parser.typedParse`
  (uncommitted as of this writing - see "Next step" below). Uses
  `NDTiffStorage.getDataSetSize()` against a 2GB threshold
  (`VIRTUAL_STACK_THRESHOLD_BYTES`, matching classic ImageJ1's own large-TIFF
  prompt threshold) and, if exceeded and `UIService.isHeadless()` is false,
  shows a yes/no `UIService.showDialog(...)`. Answering yes calls
  `config.imgOpenerSetImgModes(SCIFIOConfig.ImgMode.CELL)`, which makes
  `ImgOpener` build a lazily-loaded `CellImg` instead of reading every plane
  into memory - `ImgOpener` reads `imgOpenerGetImgModes()` back off the same
  `SCIFIOConfig` instance it hands down into `typedParse`, so this works
  regardless of entry point (drag-and-drop, File > Open, direct `ImgOpener`
  call). When virtual-stack mode is chosen, `imgOpenerSetComputeMinMax` is
  also left `false` (skipping the global min/max scan), since scanning every
  plane up front would defeat the point of not loading the dataset eagerly.
  Adding the new `@Parameter UIService uiService` field to `Parser` meant
  every restricted `Context(Class...)` that constructs one now needs
  `UIService.class` in its list too - same pattern already documented above
  for `FormatService`. Both `NDTiffFormatTest`'s and `ManualOpen`'s Context
  lists were missing it (caught before this was committed - would have
  failed every test with "Required service is missing: UIService" the
  moment `format.createParser()` ran), now fixed. Not yet covered by a
  regression test: unit-testing the actual >2GB dialog branch would need a
  real 2GB+ on-disk dataset (impractical to generate in a test), and the
  project has no mocking library to fake `NDTiffStorage.getDataSetSize()`
  instead. In the test `Context`, `UIService` resolves to
  `org.scijava.ui.DefaultUIService`, which reports `isHeadless() == true`
  with no Swing UI plugin registered, so the dialog branch is a no-op there
  regardless - existing tests only got as far as confirming the code
  compiles/injects correctly, not that the dialog itself fires correctly.
- **macOS AppleDouble files break NDTiffStorage on network drives**: found
  via real-dataset debugging (see "Debugging with ManualOpen" below).
  `NDTiffStorage`'s own `ResolutionLevel.openExistingDataSet()` lists the
  dataset directory and matches files by suffix only (`.tif`/`.TIF`/`index`),
  with no filter hook exposed to callers. On a folder that's ever touched a
  non-native filesystem (SMB/network shares, like this project's own working
  directory), macOS's `._NDTiff.index`/`._*.tif` sidecar files match those
  same suffixes and get parsed as if real, silently corrupting the result
  (observed as `indexMap`/`summaryMetadata_` ending up null - see below).
  Worse, `openExistingDataSet()` swallows whatever actually goes wrong with a
  bare `catch (Exception e) { JOptionPane.showMessageDialog(...); }` and
  never rethrows or logs `e`, so the real cause is invisible unless you
  catch it yourself (e.g. a Java Exception Breakpoint in Eclipse). Forking
  the library to fix this properly wasn't worth it - `ResolutionLevel` is
  `final` and entangled with several package-private helper classes, so
  "fork" would mean vendoring most of the library and losing future upstream
  fixes for a few lines' worth of problem. Fixed instead in
  `Parser.typedParse` (`NDTiffFormat.java`): recursively delete any
  `._*`-named file under the dataset directory before constructing
  `NDTiffStorage` at all. These files carry no real content (pure Finder
  metadata bookkeeping - the same junk `dot_clean` removes), so deleting them
  is safe. Worth reporting upstream to `micro-manager/NDStorage` at some
  point, but this local fix is what actually matters for this plugin.
- **SCIFIO trims trailing length-1 axes**: found the same way, via real-
  dataset debugging. `ImageMetadata.getAxesLengthsNonPlanar()` (and
  `getAxes()`/`getPlaneCount()`) silently drop trailing axes whose length is
  1 (`AbstractImageMetadata.getEffectiveAxes()` - meant to hide meaningless
  singleton dimensions in the UI). `Reader.openPlane` originally used that
  array directly for `FormatTools.rasterToPosition(...)`, but still iterated
  its own untrimmed `presentAxes` list alongside it - so any real dataset
  where the *last* axis in `AXIS_ORDER` (`z, channel, time, position`) has
  only one distinct value (e.g. a single-timepoint acquisition) hit an
  `ArrayIndexOutOfBoundsException` there. None of the original tests caught
  this because none used a trailing single-valued axis. Fixed by computing
  the lengths array for `rasterToPosition` directly from `presentAxes`/
  `axisValues` (which we own and SCIFIO never touches) instead of asking
  `imageMeta` for it - safe because a trimmed axis always has length 1, so
  its position is always 0 regardless of which array computes it. Regression
  test: `testOpenPlaneWithTrailingSingletonAxis`.
- **`io.scif.filters.FileStitcher` and multi-file datasets**: raised as a
  concern (not yet actually observed as a failure) after seeing
  `[ERROR] Cannot create plugin: io.scif.filters.FileStitcher` in
  `ManualOpen` output - that specific message is harmless, just
  `ManualOpen`'s narrow `Context` missing `FilePatternService` (added to its
  list; real Fiji has every service so this never happens there). But it was
  worth checking what `FileStitcher` actually *does* once constructed,
  since NDTiff datasets over 4GB do span multiple `*_NDTiffStack_N.tif`
  files. Read its source: it operates on filename patterns among the
  *siblings* of whatever `Location` it's given, and for us that's always
  the whole dataset directory (`NDTiffStorage` already hides the individual
  `_N.tif` continuation files - `FileStitcher` never sees them). In
  practice it would almost always no-op, but there's a real edge case: if a
  lab stores dataset folders side by side with sequential numeric names
  (`Experiment1`, `Experiment2`, ...), it could misidentify them as one
  artificially stitched series. Closed this off properly rather than
  relying on the coincidence not happening: `Reader` now overrides
  `Groupable`'s `fileGroupOption`/`isSingleFile`/`hasCompanionFiles` to
  declare `MUST_GROUP`, matching exactly what SCIFIO's own bundled support
  for the *older* Micro-Manager format (`io.scif.formats
  .MicromanagerFormat`) already does for the same reason - a real,
  sanctioned SCIFIO idiom for "this Location is already the whole group,
  don't go looking at its siblings," not something invented for this.
- **`NDTiffStorage.close()` isn't safe to call twice**: found via
  `ManualOpen` teardown - `ResolutionLevel.close()` (inside `NDTiffStorage`)
  nulls its internal reader map at the end of the method, with no guard
  against a second call, which then NPEs immediately on that null map
  (`java.lang.NullPointerException` at `ResolutionLevel.close():283`, on
  the background thread `NDTiffStorage.close()` spawns). Nothing prevents
  SCIFIO/a caller from closing the same `Metadata` instance more than once,
  so `Metadata.close(boolean)` now nulls its own `storage` field right after
  calling `storage.close()`, making our side idempotent instead of relying
  on the library to tolerate a double close.
- **`NDTiffStorage` prints "Couldn't read displaysettings" to `System.err`
  for basically every dataset**: another real upstream bug, not specific to
  this plugin (the user also saw it from a separate MATLAB-based NDTiff
  reader). Confirmed by reading `NDTiffStorage.java` source (downloaded
  `NDTiffStorage-2.18.4-sources.jar`): the read-existing-dataset
  constructor (`NDTiffStorage(String dir)`, around line 146) tries to read
  an optional `display_settings.txt` (only written if the dataset was ever
  displayed live in Micro-Manager's own viewer - true for essentially none
  of this lab's datasets), and on *any* exception - including the totally
  normal `NoSuchFileException` when that file doesn't exist - unconditionally
  does `System.err.println("Couldn't read displaysettings")`. It doesn't use
  the class's own opt-in `debugLogger_` callback (used everywhere else in
  the file for exactly this kind of diagnostic), and doesn't distinguish
  "file missing, nothing wrong" from "file present but corrupt, worth
  knowing about." Worth a PR to `micro-manager/NDStorage`: check
  `Files.exists(...)` first (or catch `NoSuchFileException` specifically)
  and stay silent in that case, routing a real parse failure through
  `debugLogger_` like the rest of the class does instead of a raw
  `System.err.println`. Not something we can fix by forking (same
  "vendoring the whole library for one bug" tradeoff as the AppleDouble
  issue above) - worked around instead in `Parser.typedParse`: swaps
  `System.err` for a filtering `PrintStream` (`Parser.silencing`) around
  just the `new NDTiffStorage(...)` call, dropping only a line that matches
  `Parser.DISPLAY_SETTINGS_WARNING` exactly and passing everything else
  through unchanged - so this can't hide some other, unrelated error that
  happens to print during that same window. Regression test:
  `testParseSuppressesUpstreamDisplaySettingsWarning` (exercises the real
  code path, since none of this project's synthetic test datasets ever
  write a `display_settings.txt` either).

## Building a portable distribution

For distributing to other machines (mix of Mac/PC), pom.xml now uses
`maven-shade-plugin` to produce a single self-contained jar instead of
requiring three separate jars per machine:

- `scifio`/`scijava-common`/`imagej-common` dependencies are `provided`
  scope - present on the compile/test classpath (needed to build and run
  this project's own tests) but deliberately left out of the packaged jar,
  since every real Fiji install already has compatible versions of these
  (copying a second set in risks duplicate-class conflicts - see
  "Installing into a real Fiji" below).
- `NDTiffStorage`/`MMCoreJ` stay at the default `compile` scope, since Fiji
  does not bundle either of these.
- `maven-shade-plugin`'s `shade` goal (bound to the `package` phase) bundles
  everything that isn't `provided`/`test` scope directly into
  `target/NDTiff-FIJI-<version>.jar` - so with the scope split above, it
  picks up exactly `NDTiffStorage`+`MMCoreJ` and nothing else, no explicit
  include/exclude filters needed. Verified by checking both dependencies'
  own poms (`~/.m2/repository/org/micro-manager/.../*.pom`) - neither has
  further transitive dependencies beyond each other, so those two jars are
  the complete non-Fiji dependency set.
- No relocation/shading of package names was needed - neither dependency's
  classes collide with anything Fiji already ships, confirmed by checking
  their jars' contents don't overlap with `io.scif`/`org.scijava`/
  `net.imagej` packages.
- `createDependencyReducedPom` is set `false` to stop the shade plugin from
  writing a `dependency-reduced-pom.xml` file into the project root (a
  known m2e/Eclipse-project-view annoyance, not needed here since this pom
  isn't itself published as a dependency for anything else to resolve).

Build with Eclipse's Run As → Maven build... using goal `package` (same as
before - the shade plugin's `shade` goal is bound to that phase, so no
separate step is needed). The resulting `target/NDTiff-FIJI-0.1.jar` is the
one file to copy to another machine's Fiji `jars/` folder - see "Installing
into a real Fiji" below. This has been checked by hand against the declared
dependency poms and jar contents, but not yet run through an actual build
(no `mvn` CLI on this machine, Eclipse + m2e only) - confirm in Eclipse that
`package` completes and that the resulting jar's size/content
(`jar tf target/NDTiff-FIJI-0.1.jar | grep micromanager` should list
NDTiffStorage/MMCoreJ classes) look right before distributing it.

## Installing into a real Fiji

The dev/test Fiji install on this machine is `/Applications/Fiji/` (the
`Fiji.app` inside it is just the thin macOS `.app` bundle wrapper - `jars/`,
`plugins/`, `db.xml.gz` etc. all live directly under `/Applications/Fiji/`).
Its bundled `scifio-0.46.0.jar`/`imagej-common-2.1.1.jar` are exact version
matches for what this project builds against, and `scijava-common-2.100.0
.jar` is one minor version ahead of ours (2.99.0) - close enough to not be a
concern. It also already ships `scifio-jai-imageio-1.1.1.jar`, confirming
the "permanently broken artifact" problem (see pom.xml notes above) is
specific to fetching that artifact from Maven, not the jar itself.

To install: build with `mvn package` (via Eclipse's Run As → Maven build...
- see "gotchas" below for why a plain Eclipse JAR export isn't enough), then
copy the one shaded jar, `target/NDTiff-FIJI-0.1.jar`, into
`/Applications/Fiji/jars/`. As of the `maven-shade-plugin` addition to
pom.xml (see "Building a portable distribution" below), that single jar
already has `NDTiffStorage`/`MMCoreJ` bundled in, replacing the older
three-separate-jars install process (kept here for history: it used to also
require copying `NDTiffStorage-2.18.4.jar` and `MMCoreJ-10.1.1.0.jar` in
from `~/.m2/repository/org/micro-manager/...`). Do **not** copy
scifio/scijava-common/imagej-common jars in - Fiji already has compatible
versions, and a second copy risks duplicate-class conflicts (this is also
why those three are `provided` scope in pom.xml - it keeps the shade plugin
from bundling them). Fiji has to be
fully quit and relaunched afterward (it won't pick up new jars/ contents
otherwise). Confirmed working this session against a real
Micro-Manager-acquired dataset via drag-and-drop.

Maven build gotchas hit on this machine (both stem from the project living
on an SMB-mounted network drive, `/Volumes/DICKINSON/...`):
- Eclipse's **Coverage As → Maven build...** (as opposed to **Run As →
  Maven build...**) auto-injects a JaCoCo `-javaagent` into the forked test
  JVM. JaCoCo's agent calls `FileChannel.lock()` on its output file, which
  network-mounted volumes typically don't support (`IOException: Operation
  not supported`), crashing the forked JVM before any test runs. Fix: use
  Run As, not Coverage As; or if JaCoCo is wired in some other way, add
  `-Djacoco.skip=true` to the Maven goals.
- macOS AppleDouble `._*` files (see the NDTiffStorage note above) can
  reappear under `target/` from the build itself, not just from Finder
  touching `src/`, and will crash things again. `defaults write
  com.apple.desktopservices DSDontWriteNetworkStores true` (then
  unmount/remount, or log out and back in) stops macOS writing new ones;
  existing ones still need `find "<project dir>" -name '._*' -type f
  -delete` (or `dot_clean`) to clear out.

## Debugging with ManualOpen

`src/test/java/io/scif/formats/ManualOpen.java` is a manual (not JUnit)
debug entry point added this session: it opens a real dataset folder
(passed as the one program argument) through the same
`DatasetIOService`/`FormatService`/Checker/Parser/Reader pipeline SCIFIO
uses for real, so breakpoints in `NDTiffFormat.java` hit against real data,
without needing a full Fiji UI. Debug As > Java Application in Eclipse,
with the dataset folder path as the (quoted, if it contains spaces) program
argument.

It needs a *targeted* `Context`, not a bare `new Context()` - the latter
eagerly instantiates every `Service` on the classpath, including SCIFIO's
`JAIIIOServiceImpl`, which needs the JAI ImageIO/JPEG2000 jars from
`io.scif:scifio-jai-imageio` - deliberately excluded from this project's
pom.xml (see the "permanently broken artifact" note above; a real Fiji
install happens to bundle those jars anyway, so this only bites standalone
tools like this one). `new Context(Class...)` only instantiates the
services you list plus their *Service-to-Service* dependencies - it can't
see dependencies of non-Service objects created later (Checker/Parser/
Reader/ImgOpener), so those have to be listed explicitly too. As currently
written, `ManualOpen` requests: `DatasetIOService`, `DataHandleService`,
`ImgUtilityService`, `StatusService`, `PlaneConverterService`,
`InitializeService`, `FilePatternService`. If a future SCIFIO/imagej-common
version throws another "Required service is missing: ..."
`IllegalArgumentException` from
this tool, that's the same pattern - add the named service class to that
list (its own `@Parameter` fields, if any, tell you whether it needs more).

`NDTiffFormatTest.java` has the same issue for the same reason (its static
`Context` field was a bare `new Context()` too), but needs a much shorter
list - `DataHandleService` and `FormatService` only - since the tests call
the Checker/Parser/Reader directly and never go through
`DatasetIOService`/`ImgOpener`. One extra thing worth knowing for next time:
`FormatService` isn't just a `DatasetIOService` dependency - it's needed
by *every* SCIFIO plugin object, `Format` included, because they all extend
`io.scif.AbstractSCIFIOPlugin`, which declares both `LogService` and
`FormatService` as `@Parameter` fields. In `ManualOpen` this came for free
as a transitive dependency of `DatasetIOService`; in the test it didn't,
because nothing else in that shorter list pulls it in.

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

The >2GB virtual-stack-mode prompt, and the progress-bar-stays-stuck bug that
initially came with it (see the `Metadata.virtualStack` flag above), have
both been confirmed working end-to-end in a real Fiji install against an
actual over-2GB Micro-Manager-acquired dataset. That closes out every item
in the original goal (drag-and-drop, correct hyperstack dimensions, >2GB
virtual-stack prompt) except for the two below, which are implemented but
not yet built/verified.

Two things landed since, neither compiled/run yet (no `mvn` CLI on this
machine - checked by hand against decompiled dependency jars/poms and
library source only):

1. **Portable distribution** (`maven-shade-plugin` in pom.xml) - see
   "Building a portable distribution" above. Verify a `package` build
   actually produces a jar with `NDTiffStorage`/`MMCoreJ` classes bundled in
   (`jar tf target/NDTiff-FIJI-0.1.jar | grep micromanager`), and that
   dropping just that one jar into a *clean* Fiji install's `jars/` folder
   (i.e. one that's never had `NDTiffStorage-*.jar`/`MMCoreJ-*.jar` copied in
   separately) still opens a dataset correctly.
2. **Opening a single stack `.tif` file** (see
   "Opening/dragging a single stack .tif file" above) -
   `ndtiffDatasetDirectoryForStackFile` plus the three call sites that use it
   (`Checker.isFormat`, `Parser.typedParse`, `NDTiffIOPlugin.supportsOpen`).
   Four new `NDTiffFormatTest` cases cover it
   (`testCheckerRecognizesStackFile`, `testCheckerRejectsStackFileNameWithoutIndex`,
   `testCheckerRejectsUnrelatedTiffFile`, `testParseMetadataFromStackFile`)
   but haven't actually been run. Also worth a real-Fiji check specifically
   for drag-and-drop (as opposed to File > Open), since that path goes
   through `NDTiffIOPlugin.supportsOpen` first, which is easy to forget to
   update in step with the Checker (see the parenthetical in that note above
   for why).
3. **Suppressing the upstream "Couldn't read displaysettings" warning** (see
   the `NDTiffStorage` bug note above) - `Parser.silencing`/
   `Parser.DISPLAY_SETTINGS_WARNING`, wrapped around the `new
   NDTiffStorage(...)` call in `typedParse`. Covered by
   `testParseSuppressesUpstreamDisplaySettingsWarning`, not yet run. Confirm
   in a real Fiji install that the Console window no longer pops up/logs
   that message when opening a dataset - and consider filing the suggested
   fix upstream at `micro-manager/NDStorage` (see that note for what to
   propose) so this workaround can eventually be dropped.

Once all three are confirmed, commit the pending changes (`NDTiffFormat.java`,
`NDTiffIOPlugin.java`, `NDTiffFormatTest.java`, `ManualOpen.java`,
`pom.xml`).
