
package io.scif.formats;

import io.scif.Metadata;
import io.scif.config.SCIFIOConfig;
import io.scif.config.SCIFIOConfig.ImgMode;
import io.scif.filters.MetadataWrapper;
import io.scif.img.SCIFIOImgPlus;
import io.scif.services.DatasetIOService;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import net.imagej.Dataset;

import org.scijava.Priority;
import org.scijava.io.AbstractIOPlugin;
import org.scijava.io.IOPlugin;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Attr;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

/**
 * Makes dragging-and-dropping an NDTiff dataset folder - or a single one of
 * its {@code *_NDTiffStack.tif} files - onto the main Fiji window open it
 * through {@link NDTiffFormat}.
 * <p>
 * By default, Fiji's legacy drag-and-drop handler
 * ({@code net.imagej.legacy.plugin.DefaultLegacyOpener}) only calls into
 * SCIFIO's {@code FormatService} when the "Use SCIFIO when opening files
 * (BETA!)" option is enabled - which it is not, by default. It does,
 * however, unconditionally give any {@code IOPlugin} tagged with the
 * {@code eager} attribute a chance to handle the drop first. Tagging this
 * plugin {@code eager} (and giving it {@link Priority#HIGH}) means NDTiff
 * folders open correctly without requiring that global, all-formats beta
 * option to be turned on.
 * </p>
 */
@Plugin(type = IOPlugin.class, priority = Priority.HIGH, attrs = {
	@Attr(name = "eager") })
public class NDTiffIOPlugin extends AbstractIOPlugin<Dataset> {

	@Parameter
	private DatasetIOService datasetIOService;

	@Parameter
	private UIService uiService;

	@Override
	public Class<Dataset> getDataType() {
		return Dataset.class;
	}

	@Override
	public boolean supportsOpen(final Location source) {
		return source instanceof FileLocation && NDTiffFormat
			.isNDTiffLocation(((FileLocation) source).getFile());
	}

	@Override
	public boolean supportsOpen(final String source) {
		return NDTiffFormat.isNDTiffLocation(new File(source));
	}

	/**
	 * Opens every stage position of the dataset (each is its own image - see
	 * {@link NDTiffFormat}), shows all but the first in their own windows, and
	 * returns the first.
	 * <p>
	 * An {@code IOPlugin} can only return one object, and Fiji's legacy opener
	 * only displays a returned {@link Dataset} (it does nothing with a list),
	 * so the first position goes back through the normal path - legacy
	 * displays it exactly as before - and the rest are shown here via
	 * {@link UIService}, the standard way a {@link Dataset} becomes an ImageJ
	 * window in Fiji. They're shown first so the first position's window ends
	 * up in front. In a headless context nothing is shown and only the first
	 * position is returned; call
	 * {@code DatasetIOService.openAll(source, config)} with
	 * {@code imgOpenerSetOpenAllImages(true)} to get every position there.
	 * </p>
	 */
	@Override
	public Dataset open(final Location source) throws IOException {
		final List<Dataset> positions = datasetIOService.openAll(source,
			openAllConfig());
		// Before anything is displayed: Micro-Manager's channel colors and
		// display ranges, and composite mode.
		for (final Dataset dataset : positions) {
			NDTiffDisplaySettings.apply(dataset, channelDisplay(dataset));
		}
		if (!uiService.isHeadless()) {
			for (int i = 1; i < positions.size(); i++) {
				uiService.show(positions.get(i));
			}
		}
		return positions.get(0);
	}

	@Override
	public Dataset open(final String source) throws IOException {
		return open(new FileLocation(new File(source)));
	}

	/**
	 * @return The per-channel display settings the Parser stored on this
	 *         dataset's {@link NDTiffFormat.Metadata}, or an empty list. ImgOpener
	 *         attaches the reader's Metadata to each image, possibly wrapped by
	 *         SCIFIO's reader filters (e.g. min/max computation), so unwrap it.
	 */
	private static List<NDTiffDisplaySettings.Channel> channelDisplay(
		final Dataset dataset)
	{
		if (!(dataset.getImgPlus() instanceof SCIFIOImgPlus)) {
			return Collections.emptyList();
		}
		Metadata meta = ((SCIFIOImgPlus<?>) dataset.getImgPlus()).getMetadata();
		while (meta instanceof MetadataWrapper) {
			meta = ((MetadataWrapper) meta).unwrap();
		}
		return meta instanceof NDTiffFormat.Metadata ? ((NDTiffFormat.Metadata) meta)
			.getChannelDisplay() : Collections.emptyList();
	}

	/**
	 * Same defaults {@code DatasetIOService.open(Location)} uses (planar
	 * images, which ImageJ1 needs; no up-front min/max scan - though
	 * {@link NDTiffFormat}'s Parser may change both), but reading every image
	 * rather than only image 0.
	 */
	private SCIFIOConfig openAllConfig() {
		return new SCIFIOConfig(getContext()) //
			.imgOpenerSetComputeMinMax(false) //
			.imgOpenerSetImgModes(ImgMode.PLANAR) //
			.imgOpenerSetOpenAllImages(true);
	}
}
