
package io.scif.formats;

import io.scif.AbstractChecker;
import io.scif.AbstractFormat;
import io.scif.AbstractMetadata;
import io.scif.AbstractParser;
import io.scif.ByteArrayPlane;
import io.scif.ByteArrayReader;
import io.scif.Format;
import io.scif.FormatException;
import io.scif.ImageMetadata;
import io.scif.config.SCIFIOConfig;
import io.scif.util.FormatTools;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import mmcorej.TaggedImage;

import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imglib2.Interval;

import org.micromanager.ndtiffstorage.EssentialImageMetadata;
import org.micromanager.ndtiffstorage.NDTiffStorage;
import org.scijava.app.StatusService;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;

/**
 * SCIFIO format for reading Micro-Manager NDTiff datasets.
 * <p>
 * An NDTiff dataset is a <em>folder</em> (not a single file) containing an
 * {@code NDTiff.index} file plus one or more {@code *_NDTiffStack.tif} files
 * (or, for legacy multi-resolution datasets, a {@code Full resolution}
 * subfolder holding those). All the actual index/pixel reading is delegated
 * to Micro-Manager's own {@code NDTiffStorage} library
 * (github.com/micro-manager/NDStorage) - this class only adapts that API to
 * SCIFIO's Metadata/Checker/Parser/Reader model.
 * </p>
 * <p>
 * Only the axis convention Micro-Manager's acquisition engine (AcqEngJ)
 * actually writes is supported: axis keys {@code "z"}, {@code "channel"},
 * {@code "time"} and {@code "position"} (see
 * {@code org.micromanager.acqj.main.AcqEngMetadata}). A dataset using any
 * other axis key will fail to parse with a clear {@link FormatException}
 * rather than silently dropping data.
 * </p>
 * <p>
 * There is no Writer: NDTiff is read-only for this plugin's purposes.
 * </p>
 */
@Plugin(type = Format.class)
public class NDTiffFormat extends AbstractFormat {

	/**
	 * Fixed order in which the non-planar (Z/Channel/Time/Position) axes are
	 * enumerated. Index 0 varies fastest across plane indices. Matches the
	 * axis key strings Micro-Manager's AcqEngJ writes into NDTiff metadata.
	 */
	static final List<String> AXIS_ORDER = Collections.unmodifiableList(Arrays
		.asList("z", "channel", "time", "position"));

	/**
	 * Orders axis values for use as an ImageJ dimension index. Axis values in
	 * NDTiff are either numbers (indices) or strings (e.g. named channels);
	 * numbers sort numerically, everything else falls back to string order.
	 */
	static final Comparator<Object> AXIS_VALUE_COMPARATOR = (a, b) -> {
		if (a instanceof Number && b instanceof Number) {
			return Double.compare(((Number) a).doubleValue(), ((Number) b)
				.doubleValue());
		}
		return a.toString().compareTo(b.toString());
	};

	@Override
	public String getFormatName() {
		return "NDTiff (Micro-Manager)";
	}

	/**
	 * An NDTiff "file" is a directory, so there is no filename suffix to
	 * declare - detection happens entirely in {@link Checker#isFormat}.
	 */
	@Override
	protected String[] makeSuffixArray() {
		return new String[0];
	}

	/**
	 * @param dir Candidate dataset folder.
	 * @return true if {@code dir} looks like an NDTiff dataset (either
	 *         directly, or via the legacy "Full resolution" subfolder used by
	 *         older multi-resolution datasets).
	 */
	static boolean isNDTiffDirectory(final File dir) {
		if (dir == null || !dir.isDirectory()) return false;
		if (new File(dir, "NDTiff.index").isFile()) return true;
		final File fullRes = new File(dir, "Full resolution");
		return fullRes.isDirectory() && new File(fullRes, "NDTiff.index")
			.isFile();
	}

	// *** REQUIRED COMPONENTS ***

	public static class Metadata extends AbstractMetadata {

		private NDTiffStorage storage;
		private List<String> presentAxes;
		private Map<String, List<Object>> axisValues;
		private int width, height, bitDepth, byteDepth, pixelType;
		private boolean virtualStack;

		void setStorage(final NDTiffStorage storage) {
			this.storage = storage;
		}

		NDTiffStorage getStorage() {
			return storage;
		}

		/**
		 * Records whether the Parser put this dataset into virtual-stack
		 * (on-demand, {@link SCIFIOConfig.ImgMode#CELL}) mode, so the Reader
		 * knows not to report {@code openPlane} calls as sequential load
		 * progress - those calls arrive one at a time, out of order, and
		 * indefinitely (driven by whatever plane the user is currently
		 * scrolled to), not as part of a single up-front read of the whole
		 * dataset.
		 */
		void setVirtualStack(final boolean virtualStack) {
			this.virtualStack = virtualStack;
		}

		boolean isVirtualStack() {
			return virtualStack;
		}

		void setPresentAxes(final List<String> presentAxes) {
			this.presentAxes = presentAxes;
		}

		List<String> getPresentAxes() {
			return presentAxes;
		}

		void setAxisValues(final Map<String, List<Object>> axisValues) {
			this.axisValues = axisValues;
		}

		List<Object> getAxisValues(final String axis) {
			return axisValues.get(axis);
		}

		void setPixelInfo(final int width, final int height, final int bitDepth,
			final int byteDepth, final int pixelType)
		{
			this.width = width;
			this.height = height;
			this.bitDepth = bitDepth;
			this.byteDepth = byteDepth;
			this.pixelType = pixelType;
		}

		int getWidth() {
			return width;
		}

		int getHeight() {
			return height;
		}

		int getByteDepth() {
			return byteDepth;
		}

		@Override
		public void populateImageMetadata() {
			createImageMetadata(1);
			final ImageMetadata imageMeta = get(0);

			imageMeta.setOrderCertain(true);
			imageMeta.setPlanarAxisCount(2);
			imageMeta.setLittleEndian(true);
			imageMeta.setPixelType(pixelType);
			imageMeta.setBitsPerPixel(bitDepth);

			final List<CalibratedAxis> axes = new ArrayList<>();
			axes.add(new DefaultLinearAxis(Axes.X));
			axes.add(new DefaultLinearAxis(Axes.Y));
			final long[] lengths = new long[2 + presentAxes.size()];
			lengths[0] = width;
			lengths[1] = height;
			for (int i = 0; i < presentAxes.size(); i++) {
				final String axis = presentAxes.get(i);
				axes.add(new DefaultLinearAxis(axisType(axis)));
				lengths[2 + i] = axisValues.get(axis).size();
			}
			imageMeta.setAxes(axes.toArray(new CalibratedAxis[0]));
			imageMeta.setAxisLengths(lengths);
		}

		private static AxisType axisType(final String axis) {
			switch (axis) {
				case "z":
					return Axes.Z;
				case "channel":
					return Axes.CHANNEL;
				case "time":
					return Axes.TIME;
				case "position":
					// No built-in ImageJ2 axis for stage position; this creates
					// (and caches) a custom, non-spatial one.
					return Axes.get("Position");
				default:
					throw new IllegalStateException("Unhandled axis: " + axis);
			}
		}

		@Override
		public void close(final boolean fileOnly) throws IOException {
			// NDTiffStorage.close() has no guard against being called twice -
			// ResolutionLevel.close() nulls out its reader map on the way out,
			// and a second call NPEs on that null map. Nothing stops SCIFIO (or
			// a caller) from closing the same Metadata instance more than once,
			// so make this side of it idempotent instead of relying on the
			// library to tolerate a double close.
			if (storage != null) {
				storage.close();
				storage = null;
			}
			super.close(fileOnly);
		}
	}

	public static class Checker extends AbstractChecker {

		@Override
		public boolean suffixSufficient() {
			return false;
		}

		@Override
		public boolean suffixNecessary() {
			return false;
		}

		/**
		 * Ignores the byte-stream methods on {@code handle} entirely - for a
		 * directory-based format there is no byte stream, only a
		 * {@link Location} to inspect.
		 */
		@Override
		public boolean isFormat(final DataHandle<Location> handle)
			throws IOException
		{
			final Location loc = handle.get();
			if (!(loc instanceof FileLocation)) return false;
			return isNDTiffDirectory(((FileLocation) loc).getFile());
		}
	}

	public static class Parser extends AbstractParser<Metadata> {

		/**
		 * Matches classic ImageJ1's own threshold for prompting about opening a
		 * large TIFF as a virtual stack instead of loading it into memory.
		 */
		private static final long VIRTUAL_STACK_THRESHOLD_BYTES = 2L * 1024 *
			1024 * 1024;

		@Parameter
		private UIService uiService;

		@Override
		protected void typedParse(final DataHandle<Location> handle,
			final Metadata meta, final SCIFIOConfig config) throws IOException,
			FormatException
		{
			final Location loc = handle.get();
			if (!(loc instanceof FileLocation)) {
				throw new FormatException(
					"NDTiff datasets must be opened from a local folder, not: " + loc);
			}
			final File dir = ((FileLocation) loc).getFile();

			// NDTiffStorage does its own directory listing deep inside
			// ResolutionLevel.openExistingDataSet(), with no filter hook exposed
			// to callers, and no defense against non-dataset files. On a
			// network/SMB-mounted folder, macOS AppleDouble sidecar files
			// (._NDTiff.index, ._something.tif, ...) match its ".../index" and
			// ".../.tif" suffix checks just as well as the real files, silently
			// corrupting what gets parsed (see e.g. the null summary metadata
			// this caused when ._NDTiff.index got processed after the real one).
			// They carry no real content - deleting them is exactly what
			// macOS's own `dot_clean` utility does - so we do it ourselves before
			// NDTiffStorage ever sees the directory.
			deleteAppleDoubleFiles(dir);

			final NDTiffStorage storage;
			try {
				storage = new NDTiffStorage(dir.getAbsolutePath());
			}
			catch (final IOException e) {
				throw new FormatException("Could not open NDTiff dataset at " + dir,
					e);
			}
			meta.setStorage(storage);

			final Set<HashMap<String, Object>> axesSet = storage.getAxesSet();
			if (axesSet.isEmpty()) {
				throw new FormatException("NDTiff dataset at " + dir +
					" contains no images");
			}

			final Map<String, TreeSet<Object>> valueSets = new LinkedHashMap<>();
			for (final String axis : AXIS_ORDER) {
				valueSets.put(axis, new TreeSet<>(AXIS_VALUE_COMPARATOR));
			}
			for (final HashMap<String, Object> axesMap : axesSet) {
				for (final Map.Entry<String, Object> entry : axesMap.entrySet()) {
					final TreeSet<Object> values = valueSets.get(entry.getKey());
					if (values == null) {
						throw new FormatException("NDTiff dataset at " + dir +
							" uses unrecognized axis \"" + entry.getKey() +
							"\"; only " + AXIS_ORDER + " are currently supported");
					}
					values.add(entry.getValue());
				}
			}

			final List<String> presentAxes = new ArrayList<>();
			final Map<String, List<Object>> axisValues = new LinkedHashMap<>();
			for (final String axis : AXIS_ORDER) {
				final TreeSet<Object> values = valueSets.get(axis);
				if (!values.isEmpty()) {
					presentAxes.add(axis);
					axisValues.put(axis, new ArrayList<>(values));
				}
			}
			meta.setPresentAxes(presentAxes);
			meta.setAxisValues(axisValues);

			final HashMap<String, Object> representative = axesSet.iterator()
				.next();
			final EssentialImageMetadata essential = storage
				.getEssentialImageMetadata(representative);
			if (essential.rgb) {
				throw new FormatException(
					"RGB NDTiff datasets are not yet supported by this plugin");
			}
			final int byteDepth = essential.bitDepth <= 8 ? 1 : 2;
			final int pixelType = byteDepth == 1 ? FormatTools.UINT8
				: FormatTools.UINT16;
			meta.setPixelInfo(essential.width, essential.height,
				essential.bitDepth, byteDepth, pixelType);

			// ImgOpener carries this same SCIFIOConfig instance all the way
			// through from wherever it started (drag-and-drop, File > Open,
			// ImgOpener called directly, ...) down into this typedParse call,
			// then reads imgOpenerGetImgModes() back off of it afterward to
			// decide how to allocate the image - so setting ImgMode.CELL here
			// makes ImgOpener build a lazily-loaded, on-demand CellImg (SCIFIO's
			// equivalent of classic ImageJ1's "virtual stack") instead of
			// reading every plane into memory up front, regardless of which
			// entry point got us here.
			boolean computeMinMax = true;
			final long dataSetSize = storage.getDataSetSize();
			if (dataSetSize > VIRTUAL_STACK_THRESHOLD_BYTES && !uiService
				.isHeadless())
			{
				final double gb = dataSetSize / (1024.0 * 1024 * 1024);
				final DialogPrompt.Result result = uiService.showDialog(String
					.format(
						"This NDTiff dataset is %.1f GB. Open as a virtual stack "
							+ "(load planes from disk on demand) instead of loading "
							+ "it all into memory?", gb), "Large NDTiff Dataset",
					DialogPrompt.MessageType.QUESTION_MESSAGE,
					DialogPrompt.OptionType.YES_NO_OPTION);
				if (result == DialogPrompt.Result.YES_OPTION) {
					config.imgOpenerSetImgModes(SCIFIOConfig.ImgMode.CELL);
					// A virtual stack is chosen specifically to avoid reading the
					// whole dataset up front - scanning every plane for its global
					// min/max would defeat that, so skip it same as classic
					// ImageJ1 does for its own virtual stacks.
					computeMinMax = false;
					meta.setVirtualStack(true);
				}
			}

			// Many NDTiff datasets are acquired with a small dynamic range
			// (e.g. 12-bit) inside a 16-bit container; auto-scaling the display
			// range makes them visible without a manual contrast adjustment.
			if (computeMinMax) {
				config.imgOpenerSetComputeMinMax(true);
			}
		}

		private static void deleteAppleDoubleFiles(final File dir) {
			final File[] children = dir.listFiles();
			if (children == null) return;
			for (final File child : children) {
				if (child.isDirectory()) {
					deleteAppleDoubleFiles(child);
				}
				else if (child.getName().startsWith("._")) {
					child.delete();
				}
			}
		}
	}

	public static class Reader extends ByteArrayReader<Metadata> {

		@Parameter
		private StatusService statusService;

		@Override
		protected String[] createDomainArray() {
			return new String[] { FormatTools.LM_DOMAIN };
		}

		// -- Groupable API methods --
		//
		// A dataset directory can span several *_NDTiffStack_N.tif files, but
		// NDTiffStorage already handles that internally - SCIFIO's own
		// filters/FileStitcher never even sees those files, only this format's
		// top-level directory Location. Declaring MUST_GROUP here (matching
		// what SCIFIO's own built-in support for the older Micro-Manager format,
		// io.scif.formats.MicromanagerFormat, does for the same reason) tells
		// FileStitcher not to go looking for a numeric filename pattern among
		// this directory's *siblings* and try to stitch them together - which
		// would otherwise misfire if a lab happens to store dataset folders
		// side by side with sequential names (e.g. "Experiment1",
		// "Experiment2", ...).

		@Override
		public int fileGroupOption(final Location id) {
			return FormatTools.MUST_GROUP;
		}

		@Override
		public boolean isSingleFile(final Location id) {
			return false;
		}

		@Override
		public boolean hasCompanionFiles() {
			return true;
		}

		@Override
		public ByteArrayPlane openPlane(final int imageIndex,
			final long planeIndex, final ByteArrayPlane plane,
			final Interval bounds, final SCIFIOConfig config)
			throws FormatException, IOException
		{
			final Metadata meta = getMetadata();
			final ImageMetadata imageMeta = meta.get(imageIndex);

			// Deliberately not using imageMeta.getAxesLengthsNonPlanar() here:
			// SCIFIO's ImageMetadata silently trims trailing axes whose length
			// is 1 (e.g. a dataset with only one timepoint), so that array can
			// come back shorter than presentAxes whenever the *last* axis in
			// AXIS_ORDER happens to have a single value in this dataset. Since a
			// trimmed axis always has length 1, its position is always 0 either
			// way, so computing our own lengths from presentAxes/axisValues -
			// which we fully control - sidesteps the mismatch entirely.
			final List<String> presentAxes = meta.getPresentAxes();
			final long[] lengths = new long[presentAxes.size()];
			for (int i = 0; i < presentAxes.size(); i++) {
				lengths[i] = meta.getAxisValues(presentAxes.get(i)).size();
			}
			final long[] pos = FormatTools.rasterToPosition(lengths, planeIndex);

			// ImgOpener never reports read progress on its own - it's up to each
			// Reader to do it per plane. This is what actually drives the
			// progress bar at the bottom of the main Fiji window (via
			// imagej-legacy's bridge from StatusService to ij.IJ.showStatus/
			// showProgress); without it, opening a many-plane dataset is silent.
			// But that only makes sense for a single sequential up-front read of
			// the whole dataset - in virtual-stack mode, openPlane is instead
			// called on demand, one plane at a time, indefinitely, as the user
			// scrolls through the hyperstack, which would otherwise leave the
			// progress bar permanently visible and jumping back and forth to
			// whatever plane was last displayed.
			if (!meta.isVirtualStack()) {
				long totalPlanes = 1;
				for (final long length : lengths) {
					totalPlanes *= length;
				}
				statusService.showStatus((int) planeIndex + 1, (int) totalPlanes,
					"Reading plane " + (planeIndex + 1) + "/" + totalPlanes);
			}

			final HashMap<String, Object> axes = new HashMap<>();
			for (int i = 0; i < presentAxes.size(); i++) {
				final String axis = presentAxes.get(i);
				axes.put(axis, meta.getAxisValues(axis).get((int) pos[i]));
			}

			final int width = meta.getWidth();
			final int height = meta.getHeight();
			final int byteDepth = meta.getByteDepth();

			final byte[] fullPlane;
			if (meta.getStorage().hasImage(axes)) {
				final TaggedImage image = meta.getStorage().getImage(axes);
				fullPlane = toBytes(image.pix, byteDepth, imageMeta.isLittleEndian());
			}
			else {
				// Missing plane - most likely an acquisition that was aborted
				// before this position in the sequence was reached. Match
				// Micro-Manager's own viewer and show a blank image rather than
				// failing the whole dataset.
				fullPlane = new byte[width * height * byteDepth];
			}

			return crop(fullPlane, width, height, byteDepth, bounds, plane);
		}

		private static byte[] toBytes(final Object pix, final int byteDepth,
			final boolean littleEndian)
		{
			if (byteDepth == 1) {
				return (byte[]) pix;
			}
			final short[] shorts = (short[]) pix;
			final byte[] bytes = new byte[shorts.length * 2];
			final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(littleEndian
				? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
			for (final short s : shorts) {
				buffer.putShort(s);
			}
			return bytes;
		}

		private static ByteArrayPlane crop(final byte[] fullPlane,
			final int fullWidth, final int fullHeight, final int byteDepth,
			final Interval bounds, final ByteArrayPlane plane)
		{
			final long x0 = bounds.min(0);
			final long y0 = bounds.min(1);
			final long w = bounds.dimension(0);
			final long h = bounds.dimension(1);
			if (x0 == 0 && y0 == 0 && w == fullWidth && h == fullHeight) {
				return plane.populate(fullPlane, bounds);
			}
			final int rowBytes = (int) (w * byteDepth);
			final byte[] cropped = new byte[(int) (h * rowBytes)];
			for (int row = 0; row < h; row++) {
				final int srcOffset = (int) (((y0 + row) * fullWidth + x0) *
					byteDepth);
				System.arraycopy(fullPlane, srcOffset, cropped, row * rowBytes,
					rowBytes);
			}
			return plane.populate(cropped, bounds);
		}
	}
}
