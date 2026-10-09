package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the source text for a {@code (load "path")} call. The default implementation
 * reads from the local filesystem; alternative implementations (such as the browser
 * playground's in-memory map of uploaded files) let {@code load} work where no filesystem
 * is available.
 */
@FunctionalInterface
public interface SourceLoader {

	/**
	 * Returns the Lisp source text for the given path.
	 * @param path the path requested by {@code load}
	 * @return the source text to evaluate
	 * @throws IOException if the source cannot be resolved
	 */
	String load(String path) throws IOException;

	/**
	 * Whether a file exists at the given path -- what {@code probe-file} answers. The
	 * default derives it from {@link #load} (a failed read means "not there"), the same
	 * "attempt to read" pattern {@code AsdfSystems.locate} uses, so an alternative loader
	 * (the browser playground's in-memory map) answers correctly without implementing
	 * anything. A loader backed by a real filesystem should override it: reading is both
	 * wasteful and WRONG for a file that exists but is not decodable text, which the
	 * default would report as missing.
	 * @param path the path to probe
	 * @return {@code true} when the path names an existing file
	 */
	default boolean exists(String path) {
		try {
			load(path);
			return true;
		}
		catch (IOException | RuntimeException ex) {
			return false;
		}
	}

	/**
	 * Lists a DIRECTORY -- what the {@code %list-directory} primitive behind
	 * {@code directory} / {@code uiop:directory-exists-p} answers. Returns the entry
	 * NAMES (no path prefix), a subdirectory's carrying a trailing {@code /}, in whatever
	 * order the host gives them (the caller sorts); or {@code null} when the path is not
	 * a readable directory. An EMPTY directory is an empty list, which is why the return
	 * is nullable rather than just empty -- "no entries" and "not a directory" are
	 * different answers and the one probe has to carry both.
	 * <p>
	 * The default is {@code null}: a loader that is not a filesystem (the browser
	 * playground's in-memory map) has no directories, and answering "not there" is both
	 * true and the answer that makes a caller fall back rather than fail. Like
	 * {@link #exists} it must never throw.
	 * @param path the directory to list
	 * @return the entry names, or {@code null} when the path is not a readable directory
	 */
	@Nullable default List<String> listDirectory(String path) {
		return null;
	}

	/**
	 * The file's last-modification time as a Common Lisp universal time (seconds since
	 * 1900-01-01 GMT) -- what {@code file-write-date} answers. Like {@link #exists} and
	 * {@link #listDirectory} it must never throw; {@code null} means "cannot be
	 * determined", which is the answer Common Lisp prescribes and also the default here,
	 * since a loader that is not a filesystem (the browser playground's in-memory map)
	 * has no modification times to report.
	 * @param path the path to stat
	 * @return the universal time, or {@code null} when it cannot be determined
	 */
	@Nullable default Long writeDate(String path) {
		return null;
	}

	/**
	 * The target a symbolic link holds, as written in the link -- what {@code %read-link}
	 * answers, over which {@code truename} resolves a path's links. Like {@link #exists}
	 * it must never throw; {@code null} means the path is no link (or nothing at all),
	 * which is also the default here, since a loader that is not a filesystem (the
	 * browser playground's in-memory map) has no links.
	 * @param path the path to read
	 * @return the link's target, or {@code null} when the path is no readable link
	 */
	@Nullable default String readLink(String path) {
		return null;
	}

	/**
	 * The class loader the program's Java classes come from: what a {@code java:} call
	 * and a Clojure host form resolve a class name through. A program given a Java class
	 * path ({@code --java-classpath}, {@code --java-dep}) sees it over the classes
	 * rontolisp runs with; the default is rontolisp's own loader, so a loader that does
	 * not say otherwise (the browser playground's) sees exactly those.
	 * @return the loader
	 */
	default ClassLoader javaClassLoader() {
		return SourceLoader.class.getClassLoader();
	}

	/**
	 * Adds a jar the program's dependencies bring to its Java class path -- a Clojure
	 * {@code deps.edn} library's -- so that {@link #javaClassLoader()} loads from it from
	 * now on and a compiled program carries it. Like {@link #exists} it must never throw;
	 * the default adds nothing, for a loader whose program has no Java class path of its
	 * own (the browser playground's).
	 * @param jar the jar's path
	 * @param mavenCoordinate its Maven coordinates
	 * ({@code groupId:artifactId[:extension[:classifier]]:version}) when a Maven
	 * coordinate brought it, else {@code null}
	 */
	default void addJavaClassPath(String jar, @Nullable String mavenCoordinate) {
	}

	/**
	 * Where a program's Java class path grows: the host's record of it, which the class
	 * loader it hands out reads.
	 */
	@FunctionalInterface
	interface JavaClassPathSink {

		/** Records nothing: a class path that never grows. */
		JavaClassPathSink NONE = (jar, mavenCoordinate) -> {
		};

		/**
		 * Adds a jar to the class path.
		 * @param jar the jar's path
		 * @param mavenCoordinate its Maven coordinates, or {@code null}
		 */
		void add(String jar, @Nullable String mavenCoordinate);

	}

	/**
	 * The path one file or directory is known by however it was spelled: absolute, every
	 * symbolic link resolved -- how a Clojure dependency's root is told apart from
	 * another (the oracle's {@code getCanonicalPath}). Like {@link #exists} it must never
	 * throw; a loader that is not a filesystem has nothing to resolve and answers the
	 * path itself, the default.
	 * @param path the path
	 * @return the canonical path
	 */
	default String canonicalPath(String path) {
		return path;
	}

	/**
	 * The entry names of a zip archive (a jar), in the archive's own order -- what a
	 * Clojure dependency on a jar contributes is read from the archive in place, never
	 * extracted ({@link #loadArchiveEntry}). Like {@link #exists} it must never throw;
	 * {@code null} means the path names no readable zip archive, which is also the
	 * default: a loader that is not a filesystem (the browser playground's in-memory map)
	 * holds no archive.
	 * @param path the archive's path
	 * @return the entry names, or {@code null} when the path is not a readable archive
	 */
	@Nullable default List<String> listArchive(String path) {
		return null;
	}

	/**
	 * Reads one entry of a zip archive as UTF-8 text.
	 * @param archive the archive's path
	 * @param entry the entry's name, as {@link #listArchive} gives it
	 * @return the entry's text
	 * @throws IOException if the archive or the entry cannot be read -- always, by
	 * default
	 */
	default String loadArchiveEntry(String archive, String entry) throws IOException {
		throw new IOException(archive + ": no archive can be read here");
	}

	/**
	 * Returns a loader that reads files from the local filesystem.
	 * @return a filesystem-backed loader
	 */
	static SourceLoader fileSystem() {
		return fileSystem(SourceLoader.class.getClassLoader());
	}

	/**
	 * Returns a loader that reads files from the local filesystem, for a program whose
	 * Java classes come from {@code javaClasses}.
	 * @param javaClasses the program's Java class loader ({@link #javaClassLoader()})
	 * @return a filesystem-backed loader
	 */
	static SourceLoader fileSystem(ClassLoader javaClasses) {
		return fileSystem(javaClasses, JavaClassPathSink.NONE);
	}

	/**
	 * Returns a loader that reads files from the local filesystem, for a program whose
	 * Java classes come from {@code javaClasses} and whose Java class path grows through
	 * {@code javaClassPath} -- the host's record that loader reads.
	 * @param javaClasses the program's Java class loader ({@link #javaClassLoader()})
	 * @param javaClassPath where a jar the program's dependencies bring is added
	 * ({@link #addJavaClassPath})
	 * @return a filesystem-backed loader
	 */
	static SourceLoader fileSystem(ClassLoader javaClasses, JavaClassPathSink javaClassPath) {
		return new SourceLoader() {
			@Override
			public String load(String path) throws IOException {
				return Files.readString(Path.of(path));
			}

			@Override
			public ClassLoader javaClassLoader() {
				return javaClasses;
			}

			@Override
			public void addJavaClassPath(String jar, @Nullable String mavenCoordinate) {
				javaClassPath.add(jar, mavenCoordinate);
			}

			@Override
			public boolean exists(String path) {
				try {
					return Files.exists(Path.of(path));
				}
				catch (RuntimeException ex) {
					// An unrepresentable path (a NUL byte, say) is not an existing
					// file -- probe-file answers nil rather than signalling.
					return false;
				}
			}

			@Override
			@Nullable public Long writeDate(String path) {
				try {
					// The 1900 Common Lisp epoch is 2208988800 seconds before the Unix
					// one -- the same offset get-universal-time applies.
					return Files.getLastModifiedTime(Path.of(path)).toMillis() / 1000L + 2208988800L;
				}
				catch (IOException | RuntimeException ex) {
					// Missing, unreadable or unrepresentable: "cannot be determined".
					return null;
				}
			}

			@Override
			@Nullable public String readLink(String path) {
				try {
					return Files.readSymbolicLink(Path.of(path)).toString();
				}
				catch (IOException | RuntimeException ex) {
					// No link, gone, or unrepresentable: "not a link".
					return null;
				}
			}

			@Override
			@Nullable public List<String> listDirectory(String path) {
				try (java.util.stream.Stream<Path> entries = Files.list(Path.of(path))) {
					return entries.map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
						.toList();
				}
				catch (IOException | RuntimeException ex) {
					// Not a directory, gone, or unreadable -- all "not there", the
					// answer probe-file gives for the file case.
					return null;
				}
			}

			@Override
			public String canonicalPath(String path) {
				try {
					Path absolute = Path.of(path).toAbsolutePath().normalize();
					try {
						return absolute.toRealPath().toString();
					}
					catch (IOException ex) {
						return absolute.toString(); // gone: whoever asked names the path
					}
				}
				catch (RuntimeException ex) {
					return path;
				}
			}

			@Override
			@Nullable public List<String> listArchive(String path) {
				// The central directory alone: a jar's entries are known without
				// inflating any of them, and a file cut short has none to read.
				try (ZipFile zip = new ZipFile(Path.of(path).toFile())) {
					List<String> names = new ArrayList<>();
					Enumeration<? extends ZipEntry> entries = zip.entries();
					while (entries.hasMoreElements()) {
						ZipEntry entry = entries.nextElement();
						if (!entry.isDirectory()) {
							names.add(entry.getName());
						}
					}
					return names;
				}
				catch (IOException | RuntimeException ex) {
					return null;
				}
			}

			@Override
			public String loadArchiveEntry(String archive, String entry) throws IOException {
				try (ZipFile zip = new ZipFile(Path.of(archive).toFile())) {
					ZipEntry found = zip.getEntry(entry);
					if (found == null || found.isDirectory()) {
						throw new IOException(archive + " has no entry " + entry);
					}
					try (InputStream in = zip.getInputStream(found)) {
						return new String(in.readAllBytes(), StandardCharsets.UTF_8);
					}
				}
			}
		};
	}

	/**
	 * Resolves a {@code load} path against the directory of the file doing the load, so a
	 * relative path is read relative to the loading source rather than the process
	 * working directory (like Common Lisp's {@code *load-pathname*}). An absolute path,
	 * or a path with no base directory (the top-level entry, or the REPL), is returned
	 * unchanged so it stays working-directory-relative. The result is a logical path
	 * string handed to {@link #load}; the loader decides how to read it (so a
	 * no-filesystem loader, such as the browser playground's, keeps working with
	 * {@code baseDir == null}). Path joining is purely lexical -- it never touches the
	 * filesystem.
	 * @param baseDir the directory of the loading file, or {@code null}/empty for the
	 * top-level entry (working-directory-relative)
	 * @param path the path requested by {@code load}
	 * @return the resolved logical path to pass to {@link #load}
	 */
	static String resolve(@Nullable String baseDir, String path) {
		if (baseDir == null || baseDir.isEmpty()) {
			// Top-level entry or REPL: keep working-directory-relative, and avoid any
			// java.nio path math so a no-filesystem host (browser playground) is
			// untouched.
			return path;
		}
		Path p = Path.of(path);
		if (p.isAbsolute()) {
			return path;
		}
		return Path.of(baseDir).resolve(p).normalize().toString();
	}

	/**
	 * Returns the parent directory of a (resolved) path, or {@code null} if it has none
	 * -- the base directory against which {@code load} forms inside that file resolve.
	 * @param path a resolved file path
	 * @return the parent directory, or {@code null} if the path has no parent
	 */
	@Nullable static String parentDir(String path) {
		Path parent = Path.of(path).getParent();
		return parent == null ? null : parent.toString();
	}

}
