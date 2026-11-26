package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.runtime.RontoFileModes;
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
	 * Whether a file exists at the given path -- what {@code %probe-file} answers. The
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
	 * Whether the path names a DIRECTORY, symbolic links followed -- what makes
	 * {@code %probe-file} answer a path in directory form. Like {@link #exists} it must
	 * never throw. The default derives it from {@link #listDirectory}, so a loader that
	 * lists directories answers this too, and one that is not a filesystem (the browser
	 * playground's in-memory map) has none.
	 * @param path the path to probe
	 * @return {@code true} when the path names an existing directory
	 */
	default boolean isDirectory(String path) {
		return listDirectory(path) != null;
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
	 * Makes a symbolic link holding a target as given -- what {@code %make-link} does.
	 * Like {@link #exists} it must never throw. The default makes none, since a loader
	 * that is not a filesystem (the browser playground's in-memory map) has no links.
	 * @param target what the link holds
	 * @param path where the link is made
	 * @return whether it was made
	 */
	default boolean makeLink(String target, String path) {
		return false;
	}

	/**
	 * What the host's {@code stat} (following a link in the last component) or
	 * {@code lstat} says of a path -- what {@code %file-stat} answers. Like
	 * {@link #exists} it must never throw. The default is "nothing there", since a loader
	 * that is not a filesystem (the browser playground's in-memory map) has no file to
	 * describe. The empty path names nothing, as it does to a WASI host.
	 * @param path the path to ask of
	 * @param follow whether a link in the last component is followed
	 * @return the answer
	 */
	default FileStat stat(String path, boolean follow) {
		return FileStat.refused(FileStat.MISSING);
	}

	/**
	 * Gives a path its times of last access and of last writing -- what
	 * {@code %file-set-times} does. Like {@link #exists} it must never throw.
	 * @param path the path
	 * @param follow whether a link in the last component is followed
	 * @param accessed the time of last access in nanoseconds since 1970, or {@code null}
	 * to leave it
	 * @param modified the time of last writing in nanoseconds since 1970, or {@code null}
	 * to leave it
	 * @return 0 when written, else why not ({@link FileStat#MISSING} ..
	 * {@link FileStat#OTHER})
	 */
	default int setTimes(String path, boolean follow, @Nullable Long accessed, @Nullable Long modified) {
		return FileStat.MISSING;
	}

	/**
	 * Whether the host lets the program read (4), write (2) or execute (1) what a path
	 * names -- what {@code %file-access} answers. Like {@link #exists} it must never
	 * throw. The default is false: a loader that is not a filesystem has nothing to
	 * allow.
	 * @param path the path
	 * @param what 4, 2 or 1
	 * @return the answer
	 */
	default boolean access(String path, int what) {
		return false;
	}

	/**
	 * The size of the partition holding a path (0), the bytes free in it (1) or the bytes
	 * the program may use of it (2) -- what {@code %file-space} answers. Never throws;
	 * the default is 0, a partition the loader does not have.
	 * @param path the path
	 * @param which 0, 1 or 2
	 * @return the bytes
	 */
	default long space(String path, int which) {
		return 0;
	}

	/**
	 * Gives what a path names its permission bits -- what {@code %file-set-mode} does.
	 * Never throws.
	 * @param path the path
	 * @param mode the bits, 07777
	 * @return 0 when given, else why not ({@link FileStat#MISSING} ..
	 * {@link FileStat#OTHER})
	 */
	default int setMode(String path, int mode) {
		return FileStat.MISSING;
	}

	/**
	 * Makes a file or a directory with permission bits, less the umask -- what
	 * {@code %file-make} does. Never throws.
	 * @param path the path
	 * @param mode the bits, 0777
	 * @param directory whether a directory is made
	 * @return 0 when made, else why not ({@link FileStat#MISSING} ..
	 * {@link FileStat#OTHER})
	 */
	default int make(String path, int mode, boolean directory) {
		return FileStat.MISSING;
	}

	/**
	 * What the host's {@code stat} or {@code lstat} says of a path beyond {@link #stat}'s
	 * -- what {@code %file-unix} answers. Never throws.
	 * @param path the path
	 * @param follow whether a link in the last component is followed
	 * @param names whether the owner and the group are named
	 * @return {@code RontoFileModes.unix}'s answer: the values, or the {@code Integer}
	 * reason ({@link FileStat#MISSING} .. {@link FileStat#OTHER})
	 */
	default Object unix(String path, boolean follow, boolean names) {
		return FileStat.MISSING;
	}

	/**
	 * Gives a path an owner and a group by number -- what {@code %file-set-owner} does.
	 * Never throws.
	 * @param path the path
	 * @param follow whether a link in the last component is followed
	 * @param uid the owner, -1 to leave it
	 * @param gid the group, -1 to leave it
	 * @return 0 when given, else why not ({@link FileStat#MISSING} ..
	 * {@link FileStat#OTHER}, 5 where the host does not allow it)
	 */
	default int setOwner(String path, boolean follow, int uid, int gid) {
		return FileStat.MISSING;
	}

	/**
	 * What the host's {@code stat} or {@code lstat} said of a path: its {@code type} (1 a
	 * regular file, 2 a directory, 3 a symbolic link, 4 anything else), size, times in
	 * nanoseconds since 1970 ({@code made} {@code null} where the host does not say) and
	 * permission bits ({@code null} where it has none); or, with {@code type} 0, why it
	 * could not say ({@code refusal}).
	 *
	 * @param type the type, 0 where refused
	 * @param size the size in bytes
	 * @param modified the time of last writing
	 * @param accessed the time of last access
	 * @param made the time the file was made, or {@code null}
	 * @param mode the permission bits (07777), or {@code null}
	 * @param refusal why nothing could be said, 0 where something was
	 */
	record FileStat(int type, long size, long modified, long accessed, @Nullable Long made, @Nullable Integer mode,
			int refusal) {

		/** Nothing is there, or something above the last name is no directory. */
		public static final int MISSING = 1;

		/** The host refused. */
		public static final int DENIED = 2;

		/** Too many symbolic links. */
		public static final int LOOP = 3;

		/** Another reason. */
		public static final int OTHER = 4;

		/**
		 * The answer where nothing can be said.
		 * @param why the reason
		 * @return the answer
		 */
		public static FileStat refused(int why) {
			return new FileStat(0, 0, 0, 0, null, null, why);
		}

		/**
		 * The reason a {@code java.nio.file} operation's exception gives: the JDK says
		 * {@code NoSuchFileException} where the path is missing (and where a part of it
		 * is no directory, to a stat; an operation that opens the file says
		 * {@code Not a directory}), {@code AccessDeniedException} where the host refused,
		 * and words of its own after {@code ELOOP}'s.
		 * @param failed the exception
		 * @return the reason
		 */
		public static int reason(Exception failed) {
			return RontoFileModes.why(failed);
		}

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
	 * The classes a Clojure host form lowers against where this process cannot describe
	 * the program's classes by reflection as a JVM does: in a native image, whose own
	 * reflection holds only what its build registered, the class files of the JDK it
	 * finds and of the program's Java class path. {@code null} (the default) lowers
	 * against {@link #javaClassLoader()}'s classes, by reflection.
	 * @return the classes, or {@code null} to reflect
	 */
	default @Nullable JavaClassLookup javaClassLookup() {
		return null;
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
	 * The directory a relative path names a file below -- what {@code %host-getcwd}
	 * answers on the interpreter, over which {@code probe-file}, {@code truename} and
	 * {@code directory} make their answers absolute. Like {@link #exists} it must never
	 * throw; {@code null} means there is none, which is also the default: a loader that
	 * is not a filesystem (the browser playground's in-memory map) resolves a relative
	 * path against nothing, as a WASI program does.
	 * @return the working directory's absolute namestring, or {@code null}
	 */
	@Nullable default String workingDirectory() {
		return null;
	}

	/**
	 * The entry names of a zip archive (a jar), in the archive's own order, a directory
	 * entry's ending with {@code /} -- what a Clojure dependency on a jar contributes is
	 * read from the archive in place, never extracted ({@link #loadArchiveEntry}). Like
	 * {@link #exists} it must never throw; {@code null} means the path names no readable
	 * zip archive, which is also the default: a loader that is not a filesystem (the
	 * browser playground's in-memory map) holds no archive.
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
	 * Reads several entries of a zip archive as UTF-8 text, the archive opened once where
	 * the loader can: a Java library's package read for the class one of its files
	 * declares beside its own. The default reads each with {@link #loadArchiveEntry}.
	 * @param archive the archive's path
	 * @param entries the entries' names, as {@link #listArchive} gives them
	 * @return the text of each entry that could be read, by name, in the order given
	 */
	default Map<String, String> loadArchiveEntries(String archive, Collection<String> entries) {
		Map<String, String> read = new LinkedHashMap<>();
		for (String entry : entries) {
			try {
				read.put(entry, loadArchiveEntry(archive, entry));
			}
			catch (IOException ex) {
				// an entry that cannot be read is left out
			}
		}
		return read;
	}

	/**
	 * Reads one entry of a zip archive's octets. The default encodes what
	 * {@link #loadArchiveEntry} answers in UTF-8.
	 * @param archive the archive's path
	 * @param entry the entry's name, as {@link #listArchive} gives it
	 * @return the entry's octets
	 * @throws IOException if the archive or the entry cannot be read
	 */
	default byte[] loadArchiveEntryBytes(String archive, String entry) throws IOException {
		return loadArchiveEntry(archive, entry).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Reads a file's octets. The default encodes what {@link #load} answers in UTF-8.
	 * @param path the path
	 * @return the file's octets
	 * @throws IOException if the file cannot be read
	 */
	default byte[] loadBytes(String path) throws IOException {
		return load(path).getBytes(StandardCharsets.UTF_8);
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
		return fileSystem(javaClasses, null, javaClassPath);
	}

	/**
	 * Returns a loader that reads files from the local filesystem, for a program whose
	 * Java classes come from {@code javaClasses} -- described, where this process cannot
	 * reflect them as a JVM does, by {@code javaClassLookup} -- and whose Java class path
	 * grows through {@code javaClassPath}.
	 * @param javaClasses the program's Java class loader ({@link #javaClassLoader()})
	 * @param javaClassLookup the classes a Clojure host form lowers against, or
	 * {@code null} to reflect ({@link #javaClassLookup()})
	 * @param javaClassPath where a jar the program's dependencies bring is added
	 * ({@link #addJavaClassPath})
	 * @return a filesystem-backed loader
	 */
	static SourceLoader fileSystem(ClassLoader javaClasses, @Nullable JavaClassLookup javaClassLookup,
			JavaClassPathSink javaClassPath) {
		return new SourceLoader() {
			@Override
			public String load(String path) throws IOException {
				return Files.readString(Path.of(path));
			}

			@Override
			public byte[] loadBytes(String path) throws IOException {
				return Files.readAllBytes(Path.of(path));
			}

			@Override
			public ClassLoader javaClassLoader() {
				return javaClasses;
			}

			@Override
			public @Nullable JavaClassLookup javaClassLookup() {
				return javaClassLookup;
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
			public boolean isDirectory(String path) {
				try {
					return Files.isDirectory(Path.of(path));
				}
				catch (RuntimeException ex) {
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
			public boolean makeLink(String target, String path) {
				try {
					Files.createSymbolicLink(Path.of(path), Path.of(target));
					return true;
				}
				catch (IOException | RuntimeException ex) {
					// Something there, refused, nothing above: the caller asks why.
					return false;
				}
			}

			@Override
			public FileStat stat(String path, boolean follow) {
				if (path.isEmpty()) {
					return FileStat.refused(FileStat.MISSING);
				}
				try {
					java.nio.file.LinkOption[] options = follow ? new java.nio.file.LinkOption[0]
							: new java.nio.file.LinkOption[] { java.nio.file.LinkOption.NOFOLLOW_LINKS };
					java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(Path.of(path),
							java.nio.file.attribute.BasicFileAttributes.class, options);
					int type = a.isRegularFile() ? 1 : a.isDirectory() ? 2 : a.isSymbolicLink() ? 3 : 4;
					return new FileStat(type, a.size(), nanos(a.lastModifiedTime()), nanos(a.lastAccessTime()),
							nanos(a.creationTime()), mode(Path.of(path), options), 0);
				}
				catch (IOException | RuntimeException ex) {
					return FileStat.refused(FileStat.reason(ex));
				}
			}

			private static long nanos(java.nio.file.attribute.FileTime time) {
				return time.to(java.util.concurrent.TimeUnit.NANOSECONDS);
			}

			// The permission bits of the unix view, null on a host without one.
			private static @Nullable Integer mode(Path at, java.nio.file.LinkOption... options) {
				try {
					return ((Integer) Files.getAttribute(at, "unix:mode", options)) & 07777;
				}
				catch (IOException | RuntimeException ex) {
					return null;
				}
			}

			@Override
			public boolean access(String path, int what) {
				return RontoFileModes.access(path, what);
			}

			@Override
			public long space(String path, int which) {
				return RontoFileModes.space(path, which);
			}

			@Override
			public int setMode(String path, int mode) {
				return RontoFileModes.setMode(path, mode);
			}

			@Override
			public int make(String path, int mode, boolean directory) {
				return RontoFileModes.make(path, mode, directory);
			}

			@Override
			public Object unix(String path, boolean follow, boolean names) {
				return RontoFileModes.unix(path, follow, names);
			}

			@Override
			public int setOwner(String path, boolean follow, int uid, int gid) {
				return RontoFileModes.setOwner(path, follow, uid, gid);
			}

			@Override
			public int setTimes(String path, boolean follow, @Nullable Long accessed, @Nullable Long modified) {
				if (path.isEmpty()) {
					return FileStat.MISSING;
				}
				try {
					Path at = Path.of(path);
					java.nio.file.attribute.BasicFileAttributeView view = follow
							? Files.getFileAttributeView(at, java.nio.file.attribute.BasicFileAttributeView.class)
							: Files.getFileAttributeView(at, java.nio.file.attribute.BasicFileAttributeView.class,
									java.nio.file.LinkOption.NOFOLLOW_LINKS);
					try {
						view.setTimes(time(modified), time(accessed), null);
					}
					catch (java.nio.file.AccessDeniedException denied) {
						// The view opens the file, which a file the program may not read
						// refuses; java.io.File writes the time of last writing by the
						// path, to the millisecond, as a WASI host writes both.
						if (modified == null || !at.toFile().setLastModified(Math.max(0, modified / 1_000_000))) {
							throw denied;
						}
					}
					return 0;
				}
				catch (IOException | RuntimeException ex) {
					return FileStat.reason(ex);
				}
			}

			private static java.nio.file.attribute.@Nullable FileTime time(@Nullable Long nanos) {
				return nanos == null ? null
						: java.nio.file.attribute.FileTime.from(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
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
			@Nullable public String workingDirectory() {
				try {
					String cwd = System.getProperty("user.dir");
					return cwd == null || cwd.isEmpty() ? null : cwd;
				}
				catch (RuntimeException ex) {
					// A host without system properties has no working directory.
					return null;
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
						names.add(entries.nextElement().getName());
					}
					return names;
				}
				catch (IOException | RuntimeException ex) {
					return null;
				}
			}

			@Override
			public String loadArchiveEntry(String archive, String entry) throws IOException {
				return new String(loadArchiveEntryBytes(archive, entry), StandardCharsets.UTF_8);
			}

			@Override
			public Map<String, String> loadArchiveEntries(String archive, Collection<String> entries) {
				Map<String, String> read = new LinkedHashMap<>();
				try (ZipFile zip = new ZipFile(Path.of(archive).toFile())) {
					for (String entry : entries) {
						ZipEntry found = zip.getEntry(entry);
						if (found != null && !found.isDirectory()) {
							try (InputStream in = zip.getInputStream(found)) {
								read.put(entry, new String(in.readAllBytes(), StandardCharsets.UTF_8));
							}
						}
					}
				}
				catch (IOException | RuntimeException ex) {
					// what could not be read is left out
				}
				return read;
			}

			@Override
			public byte[] loadArchiveEntryBytes(String archive, String entry) throws IOException {
				try (ZipFile zip = new ZipFile(Path.of(archive).toFile())) {
					ZipEntry found = zip.getEntry(entry);
					if (found == null || found.isDirectory()) {
						throw new IOException(archive + " has no entry " + entry);
					}
					try (InputStream in = zip.getInputStream(found)) {
						return in.readAllBytes();
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
