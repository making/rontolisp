package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

/**
 * The compiler a JVM class whose Clojure program lowers as it runs carries
 * ({@link JvmRunTimeLowering}): every class and resource of the rontolisp build that
 * compiled it -- the front end, the JVM backend, the run-time libraries -- read off the
 * build's own code source, a jar or a classes directory, once per process. The class's
 * output holds them beside it ({@code -o App.class}) or inside it ({@code -o app.jar}),
 * the travelling runtime classes' way, so the output runs with nothing on its class path.
 * <p>
 * A build with no code source to read -- a native image -- carries none: such a class
 * needs the rontolisp jar on its class path to lower as it runs.
 */
final class RunTimeCompilerFiles {

	private static volatile @Nullable Map<String, byte[]> files;

	private RunTimeCompilerFiles() {
	}

	/**
	 * @return every file of the build, by its path within an output tree, or an empty map
	 * where the build has no code source to read
	 */
	static Map<String, byte[]> read() {
		Map<String, byte[]> read = files;
		if (read == null) {
			synchronized (RunTimeCompilerFiles.class) {
				read = files;
				if (read == null) {
					read = Map.copyOf(load());
					files = read;
				}
			}
		}
		return read;
	}

	private static Map<String, byte[]> load() {
		CodeSource source = RunTimeCompilerFiles.class.getProtectionDomain().getCodeSource();
		URL location = source == null ? null : source.getLocation();
		if (location == null || !"file".equals(location.getProtocol())) {
			return Map.of();
		}
		Path root;
		try {
			root = Path.of(location.toURI());
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			return Map.of();
		}
		Map<String, byte[]> out = new LinkedHashMap<>();
		try {
			if (Files.isDirectory(root)) {
				try (Stream<Path> walk = Files.walk(root)) {
					for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
						String path = root.relativize(file).toString().replace('\\', '/');
						if (carried(path)) {
							out.put(path, Files.readAllBytes(file));
						}
					}
				}
			}
			else {
				try (ZipFile jar = new ZipFile(root.toFile())) {
					Enumeration<? extends ZipEntry> entries = jar.entries();
					while (entries.hasMoreElements()) {
						ZipEntry entry = entries.nextElement();
						if (!entry.isDirectory() && carried(entry.getName())) {
							try (InputStream in = jar.getInputStream(entry)) {
								out.put(entry.getName(), in.readAllBytes());
							}
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out;
	}

	/** The build's own files: everything but its packaging metadata. */
	private static boolean carried(String path) {
		return !path.startsWith("META-INF/") && !path.contains("..");
	}

}
