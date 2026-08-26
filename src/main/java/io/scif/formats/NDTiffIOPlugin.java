
package io.scif.formats;

import io.scif.services.DatasetIOService;

import java.io.File;
import java.io.IOException;

import net.imagej.Dataset;

import org.scijava.Priority;
import org.scijava.io.AbstractIOPlugin;
import org.scijava.io.IOPlugin;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Attr;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

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

	@Override
	public Dataset open(final Location source) throws IOException {
		return datasetIOService.open(source);
	}

	@Override
	public Dataset open(final String source) throws IOException {
		return datasetIOService.open(new FileLocation(new File(source)));
	}
}
