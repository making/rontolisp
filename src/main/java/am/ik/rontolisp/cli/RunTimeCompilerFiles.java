package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
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
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * The compiler a JVM class whose Clojure program lowers as it runs carries
 * ({@link JvmRunTimeLowering}): every class and resource of the rontolisp build that
 * compiled it -- the front end, the JVM backend, the run-time libraries -- read off the
 * build's own code source, a jar or a classes directory, once per process. The class's
 * output holds them beside it ({@code -o App.class}) or inside it ({@code -o app.jar}),
 * the travelling runtime classes' way, so the output runs with nothing on its class path.
 * <p>
 * A native image holds no class files to read: its build stores the same files as one
 * archive resource ({@link #RESOURCE}, written by {@code RunTimeCompilerFeature} from the
 * image's class path), which this reads instead -- the compiler a host runs is always the
 * build that compiled it.
 */
final class RunTimeCompilerFiles {

	/** The native image's archive of the build's files. */
	static final String RESOURCE = "am/ik/rontolisp/cli/run-time-compiler.zip";

	private static volatile @Nullable Map<String, byte[]> files;

	private RunTimeCompilerFiles() {
	}

	/**
	 * @return every file of the build, by its path within an output tree, or an empty map
	 * where the build has neither a code source nor the archive to read
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

	/**
	 * The build's files under a class path root, as the archive a native image stores
	 * ({@link #RESOURCE}): entries in path order, so one build is one sequence of bytes.
	 * @param root a classes directory or a jar
	 * @return the archive
	 */
	static byte[] archive(Path root) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> file : new TreeMap<>(filesUnder(root)).entrySet()) {
				ZipEntry entry = new ZipEntry(file.getKey());
				entry.setTime(0);
				zip.putNextEntry(entry);
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static Map<String, byte[]> load() {
		// the archive first: a native image's code source, where it has one, is the
		// executable itself
		Map<String, byte[]> out = new LinkedHashMap<>();
		ClassLoader loader = RunTimeCompilerFiles.class.getClassLoader();
		try (InputStream archive = loader == null ? null : loader.getResourceAsStream(RESOURCE)) {
			if (archive != null) {
				try (ZipInputStream zip = new ZipInputStream(archive)) {
					for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
						out.put(entry.getName(), zip.readAllBytes());
					}
				}
				return out;
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Path root = codeSource();
		return root != null ? filesUnder(root) : out;
	}

	private static @Nullable Path codeSource() {
		CodeSource source = RunTimeCompilerFiles.class.getProtectionDomain().getCodeSource();
		URL location = source == null ? null : source.getLocation();
		if (location == null || !"file".equals(location.getProtocol())) {
			return null;
		}
		try {
			return Path.of(location.toURI());
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			return null;
		}
	}

	private static Map<String, byte[]> filesUnder(Path root) {
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

	/**
	 * The build's own files: everything but its packaging metadata, the native executable
	 * output's shims and stubs (a lowering compiles to a class, never to a native
	 * executable) and a native image's own archive.
	 */
	private static boolean carried(String path) {
		return !path.startsWith("META-INF/") && !path.contains("..") && !path.startsWith(NativeToolchain.RESOURCE_ROOT)
				&& !path.equals(RESOURCE);
	}

}
