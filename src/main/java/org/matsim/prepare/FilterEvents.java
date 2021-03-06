package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import static picocli.CommandLine.*;

@Command(
		name = "filter",
		description = "Filter event file for relevant events or certain persons.",
		mixinStandardHelpOptions = true
)
public class FilterEvents implements Callable<Integer> {

	private static Logger log = LogManager.getLogger(FilterEvents.class);

	@Parameters(paramLabel = "file", arity = "1", description = "Path to event file")
	private Path input;

	@Option(names = "--ids", description = "Path to person ids to filter for.")
	private Path personIds;

	@Option(names = "--output", description = "Output file", defaultValue = "output/eventsFiltered.xml.gz")
	private Path output;


	public static void main(String[] args) {
		System.exit(new CommandLine(new FilterEvents()).execute(args));
	}

	@Override
	public Integer call() throws Exception {

		if (!Files.exists(input)) {
			log.error("Input file {} does not exists", input);
			return 2;
		}

		if (!Files.exists(output.getParent())) Files.createDirectories(output.getParent());

		Set<String> filterIds = null;
		if (Files.exists(personIds)) {
			log.info("Filtering by person events {}", personIds);
			filterIds = new HashSet<>();

			try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.getFileUrl(personIds.toString()))) {
				String line;
				while ((line = reader.readLine()) != null)
					filterIds.add(line);
			}
		}


		EventsManager manager = EventsUtils.createEventsManager();

		FilterHandler handler = new FilterHandler(null, filterIds);
		manager.addHandler(handler);
		EventsUtils.readEvents(manager, input.toString());

		EventWriterXML writer = new EventWriterXML(
				IOUtils.getOutputStream(IOUtils.getFileUrl(output.toString()), false)
		);

		log.info("Filtered {} out of {} events = {}%", handler.events.size(), handler.getCounter(), handler.events.size() / handler.getCounter());

		handler.events.forEach(writer::handleEvent);
		writer.closeFile();

		return 0;
	}

}
