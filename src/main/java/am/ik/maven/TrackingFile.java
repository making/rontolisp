package am.ik.maven;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import am.ik.artifact.AtomicInstall;
import org.jspecify.annotations.Nullable;

/**
 * Maven Resolver's update tracking files in the local repository, in its format (a
 * properties file), so {@code mvn}, {@code clj} and this resolver honor each other's
 * records: {@code resolver-status.properties} beside cached {@code maven-metadata-ID.xml}
 * files ({@code maven-metadata-central.xml.lastUpdated}, {@code .error} empty for "not
 * found"), and {@code FILE.lastUpdated} beside an artifact no repository had (keyed by
 * repository URL).
 */
final class TrackingFile {

	private static final String COMMENT = "NOTE: This is a Maven Resolver internal implementation file, its format can "
			+ "be changed without prior notice.";

	private TrackingFile() {
	}

	/**
	 * Reads a tracking file.
	 * @param file the file
	 * @return its properties, empty when it is missing or unreadable (as Maven treats it)
	 */
	static Properties read(Path file) {
		Properties properties = new Properties();
		try {
			properties.load(new ByteArrayInputStream(Files.readAllBytes(file)));
		}
		catch (NoSuchFileException ex) {
			// never tracked
		}
		catch (IOException | IllegalArgumentException ex) {
			// an unreadable record is no record
		}
		return properties;
	}

	/**
	 * Updates a tracking file: each entry set, a {@code null} value removed; the file is
	 * deleted when nothing is left.
	 * @param file the file
	 * @param updates the changes
	 * @throws MavenResolutionException if the file cannot be written
	 */
	static void update(Path file, Map<String, @Nullable String> updates) throws MavenResolutionException {
		Properties properties = read(file);
		for (Map.Entry<String, @Nullable String> update : updates.entrySet()) {
			String value = update.getValue();
			if (value == null) {
				properties.remove(update.getKey());
			}
			else {
				properties.setProperty(update.getKey(), value);
			}
		}
		try {
			if (properties.isEmpty()) {
				Files.deleteIfExists(file);
				return;
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			properties.store(bytes, COMMENT);
			AtomicInstall.writeFile(file, bytes.toByteArray());
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot write " + file + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * A recorded time.
	 * @param properties the tracking file
	 * @param key the key, without its {@code .lastUpdated} suffix
	 * @return the time in milliseconds, 1 (Maven's "unknown") when the key has none or an
	 * unreadable one
	 */
	static long lastUpdated(Properties properties, String key) {
		String value = properties.getProperty(key + ".lastUpdated", "");
		try {
			return value.isEmpty() ? 1 : Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			return 1;
		}
	}

}
