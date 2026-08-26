
package io.scif.formats;

import io.scif.img.ImgUtilityService;
import io.scif.img.converters.PlaneConverterService;
import io.scif.services.DatasetIOService;
import io.scif.services.FilePatternService;
import io.scif.services.InitializeService;

import java.io.File;

import net.imagej.Dataset;

import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.ui.UIService;

/**
 * Manual debug entry point - not a test, not shipped in the plugin jar. Opens
 * a real NDTiff dataset folder through the same
 * FormatService/Checker/Parser/Reader pipeline SCIFIO uses in production, so
 * you can set breakpoints in {@link NDTiffFormat} and step through against
 * real data.
 * <p>
 * Run/Debug As &gt; Java Application, with the dataset folder as the single
 * program argument.
 * </p>
 */
public class ManualOpen {

	public static void main(final String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println(
				"Usage: ManualOpen <path to NDTiff dataset folder>");
			System.exit(1);
		}

		// A bare `new Context()` eagerly instantiates every Service on the
		// classpath, including SCIFIO's JAIIIOServiceImpl - which needs the
		// JAI ImageIO/JPEG2000 jars from io.scif:scifio-jai-imageio, deliberately
		// excluded from this project's pom.xml (permanently broken artifact
		// upstream, unrelated to NDTiff; real Fiji installs happen to bundle
		// those jars anyway, so this only bites standalone tools like this one).
		// Listing only the services we actually need avoids instantiating that
		// one. The rest of this list (beyond DatasetIOService itself) is services
		// that get @Parameter-injected into objects SCIFIO creates on demand
		// later (Checker/Parser/Reader/ImgOpener), which Context's automatic
		// dependency resolution doesn't see coming since those objects aren't
		// Services themselves - so they have to be requested up front too.
		final Context context = new Context(DatasetIOService.class,
			DataHandleService.class, ImgUtilityService.class,
			StatusService.class, PlaneConverterService.class,
			InitializeService.class, FilePatternService.class, UIService.class);
		try {
			final DatasetIOService ioService = context.getService(
				DatasetIOService.class);
			final File dir = new File(args[0]);
			final FileLocation location = new FileLocation(dir);

			System.out.println("canOpen: " + ioService.canOpen(location));

			final Dataset dataset = ioService.open(location);
			System.out.println("Opened: " + dataset.getName());
			for (int d = 0; d < dataset.numDimensions(); d++) {
				System.out.println("  " + dataset.axis(d).type() + ": " + dataset
					.dimension(d));
			}
		}
		finally {
			context.dispose();
		}
	}
}
