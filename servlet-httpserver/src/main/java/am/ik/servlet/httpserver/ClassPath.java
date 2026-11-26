package am.ik.servlet.httpserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

/**
 * The class files of a class path, read without loading a class: the directories and
 * archives of {@code java.class.path}, the archives their manifests name
 * ({@code Class-Path}), and the directories and archives of the {@link URLClassLoader}s
 * between the application's loader and the system's.
 *
 * <p>
 * Not read: the packages of the Servlet API and of this container, {@code META-INF} (a
 * class of another release is the class of its base name) and the module path.
 */
final class ClassPath {

	private static final String CLASS = ".class";

	/** What is told of the class files of one root. */
	interface Reader {

		void read(String name, byte[] file);

	}

	private ClassPath() {
	}

	/**
	 * Reads every class file of the class path a loader sees, the roots at once, each by
	 * a reader of its own.
	 * @param classPath the roots, as the {@code java.class.path} property lists them
	 * @param loader the application's loader
	 * @param readers makes the reader of a root, told of each of its class files by its
	 * binary name
	 * @return the readers, in the order of their roots
	 */
	static <R extends Reader> List<R> read(String classPath, ClassLoader loader, Supplier<R> readers)
			throws IOException {
		List<Path> roots = roots(classPath, loader);
		List<R> read = new ArrayList<>();
		for (int i = 0; i < roots.size(); i++) {
			read.add(readers.get());
		}
		try {
			IntStream.range(0, roots.size()).parallel().forEach(i -> {
				try {
					read(roots.get(i), read.get(i));
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			});
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
		return read;
	}

	private static void read(Path root, Reader reader) throws IOException {
		if (Files.isDirectory(root)) {
			walk(root, "", reader);
			return;
		}
		try (ZipFile archive = new ZipFile(root.toFile())) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.endsWith(CLASS) && !name.endsWith("module-info.class") && !name.startsWith("META-INF/")
						&& !name.startsWith("jakarta/servlet/") && !name.startsWith("am/ik/servlet/httpserver/")) {
					try (InputStream in = archive.getInputStream(entry)) {
						reader.read(name.substring(0, name.length() - CLASS.length()).replace('/', '.'),
								in.readAllBytes());
					}
				}
			}
		}
		catch (ZipException ex) {
			// Not an archive: a class loader finds no class in it either.
		}
	}

	/**
	 * The directories and archives of the class path, each once, in the order a loader
	 * asks them: the property's, then the loaders' own, nearest the system loader first;
	 * an archive followed by those its manifest names.
	 */
	static List<Path> roots(String classPath, ClassLoader loader) throws IOException {
		List<Path> loaders = new ArrayList<>();
		for (ClassLoader at = loader; at != null; at = at.getParent()) {
			if (at instanceof URLClassLoader urlLoader) {
				URL[] urls = urlLoader.getURLs();
				for (int i = urls.length - 1; i >= 0; i--) {
					Path path = file(urls[i].toString());
					if (path != null) {
						loaders.add(0, path);
					}
				}
			}
		}
		Set<Path> roots = new LinkedHashSet<>();
		for (String entry : classPath.split(File.pathSeparator)) {
			// An empty entry is the current directory.
			add(Path.of(entry.isEmpty() ? "." : entry), roots);
		}
		for (Path path : loaders) {
			add(path, roots);
		}
		return new ArrayList<>(roots);
	}

	/** Adds a root and, an archive, the archives its manifest names. */
	private static void add(Path root, Set<Path> roots) throws IOException {
		Path normal = root.toAbsolutePath().normalize();
		if (!Files.exists(normal) || !roots.add(normal) || Files.isDirectory(normal)) {
			return;
		}
		String classPath;
		try (ZipFile archive = new ZipFile(normal.toFile())) {
			ZipEntry manifest = archive.getEntry("META-INF/MANIFEST.MF");
			if (manifest == null) {
				return;
			}
			try (InputStream in = archive.getInputStream(manifest)) {
				classPath = new Manifest(in).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
			}
		}
		catch (ZipException ex) {
			return;
		}
		if (classPath == null) {
			return;
		}
		// Relative URLs, separated by spaces, resolved against the archive's directory.
		URI base = normal.toUri();
		for (String url : classPath.trim().split(" +")) {
			if (url.isEmpty()) {
				continue;
			}
			Path named;
			try {
				named = file(base.resolve(new URI(url)).toString());
			}
			catch (URISyntaxException ex) {
				named = null;
			}
			if (named != null) {
				add(named, roots);
			}
		}
	}

	/** The file a URL names, or null where it names none. */
	private static @Nullable Path file(String url) {
		try {
			URI uri = new URI(url);
			return "file".equals(uri.getScheme()) ? Path.of(uri) : null;
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			return null;
		}
	}

	private static void walk(Path directory, String packagePrefix, Reader reader) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry)) {
					String below = packagePrefix + name + ".";
					if (!below.equals("META-INF.") && !below.equals("jakarta.servlet.")
							&& !below.equals("am.ik.servlet.httpserver.")) {
						walk(entry, below, reader);
					}
				}
				else if (name.endsWith(CLASS) && !name.equals("module-info.class")) {
					reader.read(packagePrefix + name.substring(0, name.length() - CLASS.length()),
							Files.readAllBytes(entry));
				}
			}
		}
	}

	/** The bytes of a text of ASCII. */
	static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	/** Whether a class file holds a text among its constants. */
	static boolean holds(byte[] file, byte[] sought) {
		int last = file.length - sought.length;
		for (int at = 0; at <= last; at++) {
			int matched = 0;
			while (matched < sought.length && file[at + matched] == sought[matched]) {
				matched++;
			}
			if (matched == sought.length) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The binary names of the superclass and the interfaces a class file declares, read
	 * off its constants (The Java Virtual Machine Specification, 4.1 and 4.4); none for
	 * {@code Object} and for a file that is no class file.
	 */
	static List<String> supertypes(byte[] file) {
		List<String> supertypes = new ArrayList<>();
		if (file.length < 10 || u4(file, 0) != 0xCAFEBABE) {
			return supertypes;
		}
		try {
			return parse(file, supertypes);
		}
		catch (IndexOutOfBoundsException ex) {
			// Cut short: no class file either.
			return new ArrayList<>();
		}
	}

	private static List<String> parse(byte[] file, List<String> supertypes) {
		int count = u2(file, 8);
		// Where each constant starts.
		int[] at = new int[count];
		int position = 10;
		for (int index = 1; index < count; index++) {
			at[index] = position;
			int tag = file[position] & 0xff;
			switch (tag) {
				case 1 -> position += 3 + u2(file, position + 1);
				case 7, 8, 16, 19, 20 -> position += 3;
				case 15 -> position += 4;
				case 3, 4, 9, 10, 11, 12, 17, 18 -> position += 5;
				case 5, 6 -> {
					position += 9;
					index++;
				}
				default -> {
					return supertypes;
				}
			}
		}
		int superclass = u2(file, position + 4);
		if (superclass != 0) {
			supertypes.add(className(file, at, superclass));
		}
		int interfaces = u2(file, position + 6);
		for (int i = 0; i < interfaces; i++) {
			supertypes.add(className(file, at, u2(file, position + 8 + 2 * i)));
		}
		return supertypes;
	}

	private static String className(byte[] file, int[] at, int classIndex) {
		int utf8 = at[u2(file, at[classIndex] + 1)];
		return new String(file, utf8 + 3, u2(file, utf8 + 1), StandardCharsets.UTF_8).replace('/', '.');
	}

	private static int u2(byte[] file, int at) {
		return ((file[at] & 0xff) << 8) | (file[at + 1] & 0xff);
	}

	private static int u4(byte[] file, int at) {
		return (u2(file, at) << 16) | u2(file, at + 2);
	}

}
