# NDTiff-FIJI

An ImageJ/Fiji plugin (SciJava/SCIFIO framework) that opens Micro-Manager
NDTiff files/folders. Goal: drag-and-drop an NDTiff file or folder onto the
main ImageJ window, have it open as a correctly-dimensioned ImagePlus
hyperstack, and prompt for virtual-stack mode if the dataset is >2GB — same
behavior Micro-Manager's own viewer already has, ported to ImageJ.

Full session history, the reasoning behind every fix, and next steps are in
[PROGRESS.md](PROGRESS.md) — read that before doing anything else in this
repo. In short:

- This started as a fork of `scifio/example-scifio-format`.
- `NDTiffFormat.java`/`NDTiffFormatTest.java` (`src/main/java/io/scif/formats/`,
  `src/test/java/io/scif/formats/`) are currently still the tutorial's
  placeholder logic (a fictional ".fif" format), renamed but not yet
  reimplemented for real NDTiff parsing. That reimplementation is the next
  task — see "Next step" in PROGRESS.md.
- The project builds with Eclipse + m2e. After any pom.xml edit made outside
  Eclipse's own editor, refresh (F5) the project before Maven → Update
  Project, or Eclipse won't notice the change.
- Do not re-add `io.scif:scifio-jai-imageio` as a dependency — it's excluded
  in pom.xml deliberately (permanently broken artifact upstream, unrelated to
  NDTiff).

Developer: Daniel Dickinson (dd32968@eid.utexas.edu), Dickinson Lab, UT
Austin. Repo: https://github.com/dickinson-lab/NDTiff-FIJI (local commits may
be ahead of origin/master — check before assuming GitHub is current).
