package am.ik.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

/**
 * A class path of class FILES: where a compile-time resolver finds the declared shape of
 * a class ({@link ClassFileInfo}) without loading it. The roots are searched in order,
 * and the first that holds a class wins -- the platform first, then the user's class
 * path, as {@code javac} does.
 *
 * <p>
 * The platform root is a JDK's {@code lib/ct.sym}: the signature files of every Java
 * release that JDK can compile for ({@code javac --release N}), one zip entry per class
 * and module under a directory named by the releases it belongs to ({@code 8},
 * {@code 9A}, ..., {@code LMNOP}; release 10 is {@code A}, 25 is {@code P}). A class of
 * that root is accessible only when it is public and its module exports its package to
 * every module -- the {@code module-info} of the release says which -- exactly the
 * classes a class path program can call through by reflection. A class from a directory
 * or an archive root is on the class path, in the unnamed module, and always accessible.
 *
 * <p>
 * Reads are cached; the archives stay open until {@link #close()}.
 */
public final class JvmClassPath implements AutoCloseable {

	/**
	 * A class found on the path.
	 *
	 * @param info the declared shape
	 * @param accessible whether a caller in the unnamed module (a class path program) can
	 * use the class's public members: the class is on the class path -- every class of
	 * the unnamed module is open to it -- or it is public and its module exports its
	 * package to every module
	 */
	public record Entry(ClassFileInfo info, boolean accessible) {
	}

	private final List<Root> roots;

	private final ConcurrentHashMap<String, Optional<Entry>> cache = new ConcurrentHashMap<>();

	private JvmClassPath(List<Root> roots) {
		this.roots = List.copyOf(roots);
	}

	/**
	 * A class path over a JDK's {@code ct.sym} for one release, followed by class path
	 * entries (directories and jar/zip archives).
	 * @param ctSym the {@code lib/ct.sym} file of a JDK, or {@code null} for none
	 * @param release the Java release to read from it ({@code 8} and later)
	 * @param classPath directories and archives, searched after the platform
	 * @return the class path
	 * @throws IllegalArgumentException when {@code ct.sym} holds no such release
	 * @throws UncheckedIOException when an archive cannot be opened
	 */
	public static JvmClassPath of(@Nullable Path ctSym, int release, List<Path> classPath) {
		List<Root> roots = new ArrayList<>();
		try {
			if (ctSym != null) {
				roots.add(CtSymRoot.open(ctSym, release));
			}
			for (Path path : classPath) {
				if (Files.isDirectory(path)) {
					roots.add(new DirectoryRoot(path));
				}
				else {
					roots.add(new ArchiveRoot(new ZipFile(path.toFile())));
				}
			}
		}
		catch (IOException ex) {
			closeAll(roots);
			throw new UncheckedIOException("cannot open class path entry: " + ex.getMessage(), ex);
		}
		catch (RuntimeException ex) {
			closeAll(roots);
			throw ex;
		}
		return new JvmClassPath(roots);
	}

	/**
	 * The releases a {@code ct.sym} holds signature files for.
	 * @param ctSym the {@code lib/ct.sym} file of a JDK
	 * @return the release numbers, ascending
	 * @throws UncheckedIOException when the file cannot be read
	 */
	public static List<Integer> releases(Path ctSym) {
		Set<Integer> releases = new HashSet<>();
		try (ZipFile zip = new ZipFile(ctSym.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				int slash = name.indexOf('/');
				if (slash > 0) {
					for (int i = 0; i < slash; i++) {
						int release = releaseOf(name.charAt(i));
						if (release > 0) {
							releases.add(release);
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read " + ctSym + ": " + ex.getMessage(), ex);
		}
		return releases.stream().sorted().toList();
	}

	/**
	 * Finds a class by internal name.
	 * @param internalName the internal name ({@code java/util/Map$Entry})
	 * @return the class and whether it is accessible, or {@code null} when no root holds
	 * it
	 * @throws UncheckedIOException when a root cannot be read
	 * @throws IllegalArgumentException when the file found is not a class file
	 */
	public @Nullable Entry find(String internalName) {
		return this.cache.computeIfAbsent(internalName, this::load).orElse(null);
	}

	private Optional<Entry> load(String internalName) {
		for (Root root : this.roots) {
			Entry entry = root.find(internalName);
			if (entry != null) {
				return Optional.of(entry);
			}
		}
		return Optional.empty();
	}

	@Override
	public void close() {
		closeAll(this.roots);
	}

	private static void closeAll(List<Root> roots) {
		for (Root root : roots) {
			root.close();
		}
	}

	/** The directory letter of a release in {@code ct.sym}: 8, 9, then A = 10. */
	static char releaseLetter(int release) {
		if (release < 8 || release > 35) {
			throw new IllegalArgumentException("unsupported Java release " + release);
		}
		return release < 10 ? (char) ('0' + release) : (char) ('A' + release - 10);
	}

	private static int releaseOf(char letter) {
		if (letter >= '0' && letter <= '9') {
			return letter - '0';
		}
		if (letter >= 'A' && letter <= 'Z') {
			return letter - 'A' + 10;
		}
		return -1;
	}

	private static byte[] read(ZipFile zip, ZipEntry entry) {
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read " + entry.getName() + ": " + ex.getMessage(), ex);
		}
	}

	private interface Root {

		@Nullable Entry find(String internalName);

		void close();

	}

	/**
	 * One release of a {@code ct.sym}: an index from class name to entry, and exports.
	 */
	private static final class CtSymRoot implements Root {

		private final ZipFile zip;

		private final Map<String, ZipEntry> classes;

		// Package (internal name) -> whether its module exports it to every module. A
		// package of a module without a module-info in this release (release 8) is
		// exported.
		private final Map<String, Boolean> exported;

		private CtSymRoot(ZipFile zip, Map<String, ZipEntry> classes, Map<String, Boolean> exported) {
			this.zip = zip;
			this.classes = classes;
			this.exported = exported;
		}

		static CtSymRoot open(Path ctSym, int release) throws IOException {
			char letter = releaseLetter(release);
			ZipFile zip = new ZipFile(ctSym.toFile());
			try {
				Map<String, ZipEntry> classes = new HashMap<>();
				Map<String, Set<String>> modulePackages = new HashMap<>();
				Map<String, ZipEntry> moduleInfos = new HashMap<>();
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					int releaseEnd = name.indexOf('/');
					if (entry.isDirectory() || releaseEnd < 0 || name.lastIndexOf(letter, releaseEnd) < 0
							|| !name.endsWith(".sig")) {
						continue;
					}
					int moduleEnd = name.indexOf('/', releaseEnd + 1);
					if (moduleEnd < 0) {
						continue;
					}
					String module = name.substring(releaseEnd + 1, moduleEnd);
					String className = name.substring(moduleEnd + 1, name.length() - ".sig".length());
					if ("module-info".equals(className)) {
						moduleInfos.put(module, entry);
						continue;
					}
					classes.put(className, entry);
					int lastSlash = className.lastIndexOf('/');
					String pkg = lastSlash < 0 ? "" : className.substring(0, lastSlash);
					modulePackages.computeIfAbsent(module, m -> new HashSet<>()).add(pkg);
				}
				if (classes.isEmpty()) {
					throw new IllegalArgumentException(ctSym + " holds no signature files for Java release " + release);
				}
				Map<String, Boolean> exported = new HashMap<>();
				for (Map.Entry<String, Set<String>> module : modulePackages.entrySet()) {
					ZipEntry moduleInfo = moduleInfos.get(module.getKey());
					Set<String> exports = moduleInfo == null ? module.getValue()
							: new HashSet<>(ClassFileInfo.parse(read(zip, moduleInfo)).exports());
					for (String pkg : module.getValue()) {
						exported.put(pkg, exports.contains(pkg));
					}
				}
				return new CtSymRoot(zip, classes, exported);
			}
			catch (RuntimeException ex) {
				zip.close();
				throw ex;
			}
		}

		@Override
		public @Nullable Entry find(String internalName) {
			ZipEntry entry = this.classes.get(internalName);
			if (entry == null) {
				return null;
			}
			int lastSlash = internalName.lastIndexOf('/');
			String pkg = lastSlash < 0 ? "" : internalName.substring(0, lastSlash);
			ClassFileInfo info = ClassFileInfo.parse(read(this.zip, entry));
			return new Entry(info, info.isPublic() && this.exported.getOrDefault(pkg, false));
		}

		@Override
		public void close() {
			try {
				this.zip.close();
			}
			catch (IOException ex) {
				// Read-only: nothing to lose.
			}
		}

	}

	private static final class DirectoryRoot implements Root {

		private final Path directory;

		DirectoryRoot(Path directory) {
			this.directory = directory;
		}

		@Override
		public @Nullable Entry find(String internalName) {
			Path file = this.directory.resolve(internalName + ".class");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				return new Entry(ClassFileInfo.parse(Files.readAllBytes(file)), true);
			}
			catch (IOException ex) {
				throw new UncheckedIOException("cannot read " + file + ": " + ex.getMessage(), ex);
			}
		}

		@Override
		public void close() {
		}

	}

	private static final class ArchiveRoot implements Root {

		private final ZipFile zip;

		ArchiveRoot(ZipFile zip) {
			this.zip = zip;
		}

		@Override
		public @Nullable Entry find(String internalName) {
			ZipEntry entry = this.zip.getEntry(internalName + ".class");
			return entry == null ? null : new Entry(ClassFileInfo.parse(read(this.zip, entry)), true);
		}

		@Override
		public void close() {
			try {
				this.zip.close();
			}
			catch (IOException ex) {
				// Read-only: nothing to lose.
			}
		}

	}

}
