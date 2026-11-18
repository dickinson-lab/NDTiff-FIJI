
package io.scif.formats;

import static io.scif.formats.NDTiffFormat.Checker;
import static io.scif.formats.NDTiffFormat.Metadata;
import static io.scif.formats.NDTiffFormat.Parser;
import static io.scif.formats.NDTiffFormat.Reader;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.scif.ByteArrayPlane;
import io.scif.FormatException;
import io.scif.ImageMetadata;
import io.scif.config.SCIFIOConfig;
import io.scif.img.ImgUtilityService;
import io.scif.img.converters.PlaneConverterService;
import io.scif.services.DatasetIOService;
import io.scif.services.FilePatternService;
import io.scif.services.FormatService;
import io.scif.services.InitializeService;
import io.scif.util.FormatTools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import mmcorej.org.json.JSONObject;

import net.imagej.Dataset;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.micromanager.ndtiffstorage.NDTiffStorage;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.task.TaskService;
import org.scijava.ui.UIService;

/**
 * Tests for {@link NDTiffFormat}.
 * <p>
 * Rather than shipping a binary NDTiff fixture, these tests build tiny real
 * datasets on disk using {@code NDTiffStorage}'s own writing API (the same
 * library used to read them back), then exercise the Checker/Parser/Reader
 * exactly as Fiji would.
 * </p>
 */
public class NDTiffFormatTest {

	// A bare `new Context()` eagerly instantiates every Service on the
	// classpath, including SCIFIO's JAIIIOServiceImpl, which needs the JAI
	// ImageIO/JPEG2000 jars from io.scif:scifio-jai-imageio - deliberately
	// excluded from this project's pom.xml (see CLAUDE.md). DataHandleService
	// is needed because every SCIFIO Checker/Parser/Reader gets one
	// @Parameter-injected; FormatService because every SCIFIO plugin,
	// Format included, extends AbstractSCIFIOPlugin, which needs one too
	// (usually pulled in for free as a dependency of DatasetIOService, but
	// these tests call the Checker/Parser/Reader directly and never touch
	// DatasetIOService/ImgOpener, so it has to be requested explicitly -
	// see ManualOpen/PROGRESS.md for the same pattern at a larger scale).
	// StatusService is needed too, now that Reader.openPlane reports read
	// progress through it, and UIService because Parser now prompts about
	// virtual-stack mode for large datasets.
	private static final Context context = new Context(
		DataHandleService.class, FormatService.class, StatusService.class,
		UIService.class);
	private static final NDTiffFormat format = new NDTiffFormat();

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void oneTimeSetup() throws Exception {
		format.setContext(context);
	}

	@AfterClass
	public static void oneTimeTearDown() throws Exception {
		context.dispose();
	}

	/** Writes a tiny NDTiff dataset directly into {@code dir}. */
	private static NDTiffStorage writeDataset(final File dir,
		final List<HashMap<String, Object>> planeAxes, final int width,
		final int height) throws Exception
	{
		final NDTiffStorage storage = new NDTiffStorage(dir.getAbsolutePath(),
			"test", new JSONObject(), 0, 0, false, null, 10, null, false);
		int value = 0;
		for (final HashMap<String, Object> axes : planeAxes) {
			final short[] pixels = new short[width * height];
			Arrays.fill(pixels, (short) (100 * ++value));
			storage.putImage(pixels, new JSONObject(), axes, false, 16, height,
				width).get();
		}
		storage.finishedWriting();
		storage.closeAndWait();
		return storage;
	}

	/** Finds the dataset's stack file (e.g. {@code "test_NDTiffStack.tif"}). */
	private static File findStackFile(final File dir) {
		for (final File child : dir.listFiles()) {
			if (child.getName().endsWith("NDTiffStack.tif")) return child;
		}
		throw new AssertionError("No *NDTiffStack.tif file found in " + dir);
	}

	private static HashMap<String, Object> axes(final String k1, final int v1) {
		final HashMap<String, Object> axes = new HashMap<>();
		axes.put(k1, v1);
		return axes;
	}

	private static HashMap<String, Object> axes(final String k1, final int v1,
		final String k2, final int v2)
	{
		final HashMap<String, Object> axes = axes(k1, v1);
		axes.put(k2, v2);
		return axes;
	}

	/** Axes map with all four supported axes. */
	private static HashMap<String, Object> axes(final int z, final int channel,
		final int time, final int position)
	{
		final HashMap<String, Object> axes = axes("z", z, "channel", channel);
		axes.put("time", time);
		axes.put("position", position);
		return axes;
	}

	/**
	 * Writes a dataset with 2 Z slices x 3 stage positions (plus single-valued
	 * channel and time axes, as Micro-Manager typically writes them). Pixel
	 * values are 100, 200, ... in write order, so position p, slice z holds
	 * {@code 100 * (2 * p + z + 1)}.
	 */
	private static void writeMultiPositionDataset(final File dir)
		throws Exception
	{
		writeDataset(dir, Arrays.asList(axes(0, 0, 0, 0), axes(1, 0, 0, 0), axes(
			0, 0, 0, 1), axes(1, 0, 0, 1), axes(0, 0, 0, 2), axes(1, 0, 0, 2)), 4,
			3);
	}

	private static boolean hasAxis(final List<CalibratedAxis> axes,
		final String label)
	{
		for (final CalibratedAxis axis : axes) {
			if (axis.type().getLabel().equals(label)) return true;
		}
		return false;
	}

	@Test
	public void testCheckerRecognizesDataset() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("channel", 0)), 4, 3);

		final Checker checker = (Checker) format.createChecker();
		assertTrue(checker.isFormat(new FileLocation(dir)));
	}

	@Test
	public void testCheckerRejectsPlainFolder() throws Exception {
		final File dir = tmp.newFolder("not-a-dataset");

		final Checker checker = (Checker) format.createChecker();
		assertFalse(checker.isFormat(new FileLocation(dir)));
	}

	/**
	 * A user may open or drag-and-drop a single one of the dataset's own
	 * {@code *_NDTiffStack.tif} files instead of the enclosing folder - the
	 * Checker should still recognize the dataset in that case.
	 */
	@Test
	public void testCheckerRecognizesStackFile() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("channel", 0)), 4, 3);

		final Checker checker = (Checker) format.createChecker();
		assertTrue(checker.isFormat(new FileLocation(findStackFile(dir))));
	}

	/**
	 * A {@code .tif} file that merely happens to be named like an NDTiff stack
	 * file, but isn't actually sitting next to an {@code NDTiff.index}, must
	 * be rejected - it should fall through to Fiji's normal plain-TIFF
	 * handling instead of being misidentified as an NDTiff dataset.
	 */
	@Test
	public void testCheckerRejectsStackFileNameWithoutIndex() throws Exception {
		final File dir = tmp.newFolder("not-a-dataset");
		final File fakeStackFile = new File(dir, "SomeOther_NDTiffStack.tif");
		assertTrue(fakeStackFile.createNewFile());

		final Checker checker = (Checker) format.createChecker();
		assertFalse(checker.isFormat(new FileLocation(fakeStackFile)));
	}

	/**
	 * A plain {@code .tif} file (not matching the NDTiff stack file naming
	 * convention at all) must be rejected even if an unrelated
	 * {@code NDTiff.index} happens to sit in the same folder.
	 */
	@Test
	public void testCheckerRejectsUnrelatedTiffFile() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("channel", 0)), 4, 3);
		final File unrelated = new File(dir, "unrelated.tif");
		assertTrue(unrelated.createNewFile());

		final Checker checker = (Checker) format.createChecker();
		assertFalse(checker.isFormat(new FileLocation(unrelated)));
	}

	@Test
	public void testParseMetadata() throws Exception {
		final File dir = tmp.newFolder("dataset");
		final int width = 8;
		final int height = 6;
		writeDataset(dir, Arrays.asList(axes("channel", 0), axes("channel", 1)),
			width, height);

		final Parser parser = (Parser) format.createParser();
		final Metadata metadata = parser.parse(new FileLocation(dir));

		final ImageMetadata imgMeta = metadata.get(0);
		assertTrue(imgMeta.isLittleEndian());
		assertTrue(imgMeta.isOrderCertain());
		assertEquals(2, imgMeta.getPlanarAxisCount());
		assertEquals(FormatTools.UINT16, imgMeta.getPixelType());
		assertEquals(16, imgMeta.getBitsPerPixel());

		final List<CalibratedAxis> axes = imgMeta.getAxes();
		assertEquals(3, axes.size());
		assertEquals(Axes.X, axes.get(0).type());
		assertEquals(Axes.Y, axes.get(1).type());
		assertEquals(Axes.CHANNEL, axes.get(2).type());
		assertEquals(width, imgMeta.getAxisLength(axes.get(0)));
		assertEquals(height, imgMeta.getAxisLength(axes.get(1)));
		assertEquals(2, imgMeta.getAxisLength(axes.get(2)));
	}

	/**
	 * Parsing from the dataset's stack file directly (rather than the
	 * enclosing folder) must resolve back to the same dataset and produce the
	 * same metadata.
	 */
	@Test
	public void testParseMetadataFromStackFile() throws Exception {
		final File dir = tmp.newFolder("dataset");
		final int width = 8;
		final int height = 6;
		writeDataset(dir, Arrays.asList(axes("channel", 0), axes("channel", 1)),
			width, height);

		final Parser parser = (Parser) format.createParser();
		final Metadata metadata = parser.parse(new FileLocation(findStackFile(
			dir)));

		final ImageMetadata imgMeta = metadata.get(0);
		assertEquals(width, imgMeta.getAxisLength(imgMeta.getAxes().get(0)));
		assertEquals(height, imgMeta.getAxisLength(imgMeta.getAxes().get(1)));
		assertEquals(2, imgMeta.getAxisLength(imgMeta.getAxes().get(2)));
	}

	@Test
	public void testOpenPlaneReadsCorrectPixels() throws Exception {
		final File dir = tmp.newFolder("dataset");
		final int width = 5;
		final int height = 4;
		writeDataset(dir, Arrays.asList(axes("channel", 0), axes("channel", 1)),
			width, height);

		final Reader reader = (Reader) format.createReader();
		reader.setSource(new FileLocation(dir));

		final Interval bounds = Intervals.createMinSize(0, 0, width, height);
		for (int planeIndex = 0; planeIndex < 2; planeIndex++) {
			final ByteArrayPlane plane = reader.createPlane(bounds);
			reader.openPlane(0, planeIndex, plane, bounds, new SCIFIOConfig());

			final short[] actual = new short[width * height];
			ByteBuffer.wrap(plane.getBytes()).order(ByteOrder.LITTLE_ENDIAN)
				.asShortBuffer().get(actual);

			final short[] expected = new short[width * height];
			Arrays.fill(expected, (short) (100 * (planeIndex + 1)));
			assertArrayEquals(expected, actual);
		}
	}

	/**
	 * Regression test: SCIFIO's {@code ImageMetadata.getAxesLengthsNonPlanar()}
	 * silently trims trailing axes whose length is 1. Here "time" (the
	 * trailing present axis, per {@code AXIS_ORDER}) has only one distinct
	 * value, which used to make {@code Reader.openPlane} compute the wrong
	 * axes for every plane (or throw {@code ArrayIndexOutOfBoundsException}).
	 */
	@Test
	public void testOpenPlaneWithTrailingSingletonAxis() throws Exception {
		final File dir = tmp.newFolder("dataset");
		final int width = 4;
		final int height = 3;
		// z varies (0, 1), but time is always 0 - a single-timepoint dataset.
		writeDataset(dir, Arrays.asList(axes("z", 0, "time", 0), axes("z", 1,
			"time", 0)), width, height);

		final Reader reader = (Reader) format.createReader();
		reader.setSource(new FileLocation(dir));

		final Interval bounds = Intervals.createMinSize(0, 0, width, height);
		for (int planeIndex = 0; planeIndex < 2; planeIndex++) {
			final ByteArrayPlane plane = reader.createPlane(bounds);
			reader.openPlane(0, planeIndex, plane, bounds, new SCIFIOConfig());

			final short[] actual = new short[width * height];
			ByteBuffer.wrap(plane.getBytes()).order(ByteOrder.LITTLE_ENDIAN)
				.asShortBuffer().get(actual);

			final short[] expected = new short[width * height];
			Arrays.fill(expected, (short) (100 * (planeIndex + 1)));
			assertArrayEquals(expected, actual);
		}
	}

	@Test
	public void testMissingPlaneReturnsBlankImage() throws Exception {
		final File dir = tmp.newFolder("dataset");
		final int width = 4;
		final int height = 4;
		// channel x time, but never write (channel=1, time=1)
		writeDataset(dir, Arrays.asList(axes("channel", 0, "time", 0), axes(
			"channel", 1, "time", 0), axes("channel", 0, "time", 1)), width,
			height);

		final Reader reader = (Reader) format.createReader();
		reader.setSource(new FileLocation(dir));

		// channel varies fastest, so (channel=1, time=1) is raster index 3
		final Interval bounds = Intervals.createMinSize(0, 0, width, height);
		final ByteArrayPlane plane = reader.createPlane(bounds);
		reader.openPlane(0, 3, plane, bounds, new SCIFIOConfig());

		final byte[] expected = new byte[width * height * 2];
		assertArrayEquals(expected, plane.getBytes());
	}

	@Test(expected = FormatException.class)
	public void testUnrecognizedAxisThrows() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("grid_row", 0)), 4, 4);

		final Parser parser = (Parser) format.createParser();
		parser.parse(new FileLocation(dir));
	}

	/**
	 * Regression test for an upstream {@code NDTiffStorage} wart: its
	 * read-existing-dataset constructor unconditionally prints
	 * "Couldn't read displaysettings" to {@code System.err} whenever the
	 * optional {@code display_settings.txt} file is missing - which it always
	 * is for these test datasets (nothing here ever writes one), so this
	 * exercises the real code path rather than a contrived one. Parser must
	 * suppress exactly that message without swallowing anything else written
	 * to {@code System.err} during the same call.
	 */
	@Test
	public void testParseSuppressesUpstreamDisplaySettingsWarning()
		throws Exception
	{
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("channel", 0)), 4, 3);

		final PrintStream realErr = System.err;
		final ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured));
		try {
			final Parser parser = (Parser) format.createParser();
			parser.parse(new FileLocation(dir));
		}
		finally {
			System.setErr(realErr);
		}

		assertFalse(captured.toString().contains(
			"Couldn't read displaysettings"));
	}

	/**
	 * Each stage position must be its own image (so it opens in its own
	 * window), with no Position axis, and a title naming the position.
	 */
	@Test
	public void testEachPositionIsSeparateImage() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeMultiPositionDataset(dir);

		final Parser parser = (Parser) format.createParser();
		final Metadata metadata = parser.parse(new FileLocation(dir));

		assertEquals(3, metadata.getImageCount());
		for (int p = 0; p < 3; p++) {
			final ImageMetadata imgMeta = metadata.get(p);
			assertEquals("dataset - Position " + p, imgMeta.getName());
			assertFalse(hasAxis(imgMeta.getAxes(), "Position"));
			assertEquals(4, imgMeta.getAxisLength(Axes.X));
			assertEquals(3, imgMeta.getAxisLength(Axes.Y));
			assertEquals(2, imgMeta.getAxisLength(Axes.Z));
			assertEquals(2, imgMeta.getPlaneCount());
		}
	}

	@Test
	public void testOpenPlaneReadsEachPosition() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeMultiPositionDataset(dir);

		final Reader reader = (Reader) format.createReader();
		reader.setSource(new FileLocation(dir));

		final Interval bounds = Intervals.createMinSize(0, 0, 4, 3);
		for (int p = 0; p < 3; p++) {
			for (int z = 0; z < 2; z++) {
				final ByteArrayPlane plane = reader.createPlane(bounds);
				reader.openPlane(p, z, plane, bounds, new SCIFIOConfig());
				final short[] actual = new short[4 * 3];
				ByteBuffer.wrap(plane.getBytes()).order(ByteOrder.LITTLE_ENDIAN)
					.asShortBuffer().get(actual);
				final short[] expected = new short[4 * 3];
				Arrays.fill(expected, (short) (100 * (2 * p + z + 1)));
				assertArrayEquals("position " + p + ", z " + z, expected, actual);
			}
		}
	}

	/**
	 * A position axis with only one value must still be included in each
	 * plane lookup (NDTiffStorage indexes planes by their full axes map), and
	 * the single image keeps its default (file/folder name) title.
	 */
	@Test
	public void testSinglePositionDatasetIsOneUnnamedImage() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeDataset(dir, Arrays.asList(axes("channel", 0, "position", 0), axes(
			"channel", 1, "position", 0)), 4, 3);

		final Reader reader = (Reader) format.createReader();
		reader.setSource(new FileLocation(dir));
		final Metadata metadata = reader.getMetadata();
		assertEquals(1, metadata.getImageCount());
		assertEquals(null, metadata.get(0).getName());
		assertFalse(hasAxis(metadata.get(0).getAxes(), "Position"));

		final Interval bounds = Intervals.createMinSize(0, 0, 4, 3);
		final ByteArrayPlane plane = reader.createPlane(bounds);
		reader.openPlane(0, 1, plane, bounds, new SCIFIOConfig());
		final short[] actual = new short[4 * 3];
		ByteBuffer.wrap(plane.getBytes()).order(ByteOrder.LITTLE_ENDIAN)
			.asShortBuffer().get(actual);
		final short[] expected = new short[4 * 3];
		Arrays.fill(expected, (short) 200);
		assertArrayEquals(expected, actual);
	}

	/**
	 * End-to-end through ImgOpener: NDTiffIOPlugin returns the first
	 * position, and opening all images yields one Dataset per position with
	 * the right pixels. Uses its own Context because DatasetIOService/
	 * ImgOpener need more services than the rest of this class (same list
	 * ManualOpen uses; TaskService is needed by newer SCIFIO's ImgOpener, e.g.
	 * the 0.49.2 Fiji now bundles). UIService is forced headless so the plugin
	 * doesn't try to show windows during the test.
	 */
	@Test
	public void testIOPluginOpensEveryPosition() throws Exception {
		final File dir = tmp.newFolder("dataset");
		writeMultiPositionDataset(dir);

		final Context ctx = new Context(DatasetIOService.class,
			DataHandleService.class, ImgUtilityService.class, StatusService.class,
			PlaneConverterService.class, InitializeService.class,
			FilePatternService.class, UIService.class, TaskService.class);
		try {
			ctx.getService(UIService.class).setHeadless(true);
			final FileLocation loc = new FileLocation(dir);

			final NDTiffIOPlugin plugin = new NDTiffIOPlugin();
			ctx.inject(plugin);
			assertEquals("dataset - Position 0", plugin.open(loc).getName());

			final List<Dataset> all = ctx.getService(DatasetIOService.class)
				.openAll(loc, new SCIFIOConfig(ctx).imgOpenerSetOpenAllImages(true));
			assertEquals(3, all.size());
			for (int p = 0; p < 3; p++) {
				final Dataset ds = all.get(p);
				assertEquals("dataset - Position " + p, ds.getName());
				assertEquals(-1, ds.dimensionIndex(Axes.get("Position")));
				assertEquals(2, ds.dimension(ds.dimensionIndex(Axes.Z)));
				final RandomAccess<RealType<?>> ra = ds.randomAccess();
				ra.setPosition(new long[ds.numDimensions()]);
				assertEquals(100 * (2 * p + 1), ra.get().getRealDouble(), 0);
			}
		}
		finally {
			ctx.dispose();
		}
	}
}
