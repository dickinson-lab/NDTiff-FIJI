
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
import io.scif.util.FormatTools;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import mmcorej.org.json.JSONObject;

import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imglib2.Interval;
import net.imglib2.util.Intervals;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.micromanager.ndtiffstorage.NDTiffStorage;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;

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

	private static final Context context = new Context();
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
}
