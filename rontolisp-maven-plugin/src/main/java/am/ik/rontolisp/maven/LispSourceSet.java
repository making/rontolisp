package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.JvmSourceCompiler;

/**
 * One directory of {@code .lisp} sources compiled into one directory of {@code .class}
 * files -- the whole of what the {@code compile} goal does, with no Maven type in sight
 * so that it can be driven straight from a test.
 * <p>
 * <strong>A source set is Lisp, not a pile of exports.</strong> The files load each
 * other, most of them are ordinary Lisp with no Java caller at all, and only the ones
 * that declare a {@code rontolisp:jvm-export} have a Java-facing surface. So a file is
 * compiled to a class exactly when the class would have an ENTRY POINT: under the default
 * library mode that is its exports, and a file that declares none is left as Lisp rather
 * than failing the build. With {@code noMain} off every file has one -- {@code main} --
 * and every file compiles, the way the command line compiles a program.
 * <p>
 * <strong>The path IS the class name.</strong>
 * {@code src/main/lisp/com/acme/Kernels.lisp} becomes {@code com.acme.Kernels}, the
 * convention every JVM-language plugin uses, so no per-file declaration is needed and the
 * output lands where {@code maven-jar-plugin} already looks. Only a file that is actually
 * compiled has to be named that way: an uncompiled helper is Lisp, so
 * {@code string-utils.lisp} beside it is fine.
 */
public final class LispSourceSet {

	/** The extension a source file is recognized by. */
	public static final String EXTENSION = ".lisp";

	/** Separates a source path from its class name in the status file. */
	private static final String STATUS_SEPARATOR = "\t";

	/** What the status file records for a source that produced no class. */
	private static final String NO_CLASS = "-";

	private final Path sourceDirectory;

	private final Path outputDirectory;

	private final Path statusFile;

	private final boolean noMain;

	private final Consumer<JvmSourceCompiler> configuration;

	/**
	 * @param sourceDirectory the directory holding the {@code .lisp} tree
	 * @param outputDirectory where the {@code .class} files go
	 * @param statusFile where the previous run's source-to-class mapping is recorded, for
	 * the staleness check
	 * @param noMain library mode: the class is entered through its exports only, so a
	 * file that declares none is not compiled
	 * @param configuration applies the build's flags to each file's compiler
	 */
	public LispSourceSet(Path sourceDirectory, Path outputDirectory, Path statusFile, boolean noMain,
			Consumer<JvmSourceCompiler> configuration) {
		this.sourceDirectory = sourceDirectory;
		this.outputDirectory = outputDirectory;
		this.statusFile = statusFile;
		this.noMain = noMain;
		this.configuration = configuration;
	}

	/**
	 * What a compilation did.
	 *
	 * @param sources how many {@code .lisp} files the source directory holds
	 * @param classes the binary names of the classes written, in source order
	 * @param uncompiled the sources that produced no class because they declare no
	 * {@code rontolisp:jvm-export}, relative to the source directory
	 * @param upToDate whether nothing was stale, so nothing was compiled
	 */
	public record Result(int sources, List<String> classes, List<String> uncompiled, boolean upToDate) {
	}

	/**
	 * Compiles the source set, skipping the work entirely when nothing has changed since
	 * the last run.
	 * <p>
	 * Staleness is all-or-nothing, which is {@code maven-compiler-plugin}'s own rule and
	 * the only safe one here: a {@code (load "...")} splices one source into another, so
	 * a file whose own timestamp did not move can still need recompiling. It is read off
	 * the status file rather than off the output directory, because a source set whose
	 * files need not each produce a class cannot ask the output directory whether a
	 * missing class was SKIPPED or never built.
	 * @return what was compiled
	 * @throws IOException if the tree cannot be read or the classes cannot be written
	 */
	public Result compile() throws IOException {
		// Stamped BEFORE the work, not after: a source edited WHILE the compile runs is
		// then newer than the stamp, and the next build recompiles it.
		long started = System.currentTimeMillis();
		List<Path> sources = sources();
		if (sources.isEmpty()) {
			return new Result(0, List.of(), List.of(), true);
		}
		Result previous = upToDate(sources);
		if (previous != null) {
			return previous;
		}
		List<String> classes = new ArrayList<>();
		List<String> uncompiled = new ArrayList<>();
		List<String> status = new ArrayList<>();
		for (Path source : sources) {
			Optional<String> compiled = compileOne(source);
			(compiled.isPresent() ? classes : uncompiled).add(compiled.orElseGet(() -> relative(source)));
			status.add(relative(source) + STATUS_SEPARATOR + compiled.orElse(NO_CLASS));
		}
		writeStatus(status, started);
		return new Result(sources.size(), List.copyOf(classes), List.copyOf(uncompiled), false);
	}

	/**
	 * @return every {@code .lisp} file under the source directory, in path order
	 */
	public List<Path> sources() throws IOException {
		if (!Files.isDirectory(this.sourceDirectory)) {
			return List.of();
		}
		try (Stream<Path> tree = Files.walk(this.sourceDirectory)) {
			return tree.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
				.sorted(Comparator.comparing(Path::toString))
				.toList();
		}
	}

	/**
	 * The binary class name a source file compiles to: its path under the source
	 * directory, without the extension, with the separators turned into dots.
	 * <p>
	 * Checked only for a file that is actually compiled -- a file the source set leaves
	 * as Lisp names no class, so the Lisp convention {@code string-utils.lisp} is not an
	 * error there.
	 * @param source a file under the source directory
	 * @return the binary class name
	 * @throws IllegalArgumentException if a path segment is not a Java identifier
	 */
	public String className(Path source) {
		String name = binaryName(source);
		for (String segment : name.split("\\.", -1)) {
			if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))
					|| !segment.chars().skip(1).allMatch(Character::isJavaIdentifierPart)) {
				throw new IllegalArgumentException(source + ": '" + segment + "' is not a Java identifier, so "
						+ relative(source) + " does not name a class. The path under the source directory IS the class"
						+ " name (com/acme/Kernels" + EXTENSION + " -> com.acme.Kernels), so rename it -- or drop its"
						+ " (rontolisp:jvm-export ...) declarations, and it stays Lisp");
			}
		}
		return name;
	}

	private String binaryName(Path source) {
		String name = relative(source);
		return name.substring(0, name.length() - EXTENSION.length()).replace('/', '.');
	}

	private String relative(Path source) {
		return this.sourceDirectory.toAbsolutePath()
			.relativize(source.toAbsolutePath())
			.toString()
			.replace(File.separatorChar, '/');
	}

	/**
	 * The previous run's result when nothing has changed, and {@code null} when anything
	 * has: a source added or removed, a source touched since the status file was written,
	 * or a class it recorded no longer in the output directory (someone cleaned it).
	 */
	private Result upToDate(List<Path> sources) throws IOException {
		if (!Files.isRegularFile(this.statusFile)) {
			return null;
		}
		long stamp = Files.getLastModifiedTime(this.statusFile).toMillis();
		List<String> classes = new ArrayList<>();
		List<String> uncompiled = new ArrayList<>();
		List<String> recorded = Files.readAllLines(this.statusFile, StandardCharsets.UTF_8);
		if (recorded.size() != sources.size()) {
			return null;
		}
		for (int i = 0; i < sources.size(); i++) {
			String[] entry = recorded.get(i).split(STATUS_SEPARATOR, 2);
			if (entry.length != 2 || !entry[0].equals(relative(sources.get(i)))
					|| Files.getLastModifiedTime(sources.get(i)).toMillis() > stamp) {
				return null;
			}
			if (NO_CLASS.equals(entry[1])) {
				uncompiled.add(entry[0]);
			}
			else if (Files.isRegularFile(this.outputDirectory.resolve(entry[1].replace('.', '/') + ".class"))) {
				classes.add(entry[1]);
			}
			else {
				return null;
			}
		}
		return new Result(sources.size(), List.copyOf(classes), List.copyOf(uncompiled), true);
	}

	/**
	 * Compiles one source, and answers the class it produced -- or empty when it declares
	 * no {@code rontolisp:jvm-export} and library mode leaves it as Lisp.
	 */
	private Optional<String> compileOne(Path source) throws IOException {
		// The derived name is not CHECKED yet: whether the file has to name a class at
		// all is only known once the compile has answered whether it exports anything.
		JvmSourceCompiler compiler = new JvmSourceCompiler(binaryName(source)).noMain(this.noMain);
		// A (load "sibling.lisp") resolves against the file that wrote it, as it does on
		// the command line.
		Path parent = source.toAbsolutePath().getParent();
		compiler.baseDir(parent == null ? null : parent.toString());
		this.configuration.accept(compiler);
		Optional<JvmSourceCompiler.Result> compiled;
		try {
			String text = Files.readString(source, StandardCharsets.UTF_8);
			compiled = this.noMain ? compiler.compileIfExported(text, source.toString())
					: Optional.of(compiler.compile(text, source.toString()));
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
		catch (RuntimeException ex) {
			throw new LispCompilationException(source, ex);
		}
		if (compiled.isEmpty()) {
			return Optional.empty();
		}
		// The name is only required to BE a class name once the file has earned a class;
		// an uncompiled helper is Lisp and may be called anything.
		String className = className(source);
		write(className.replace('.', '/') + ".class", compiled.get().classBytes());
		// A :float-vector / :float-matrix export hands out a handle class. It travels at
		// its canonical name so two rontolisp libraries agree on the type, and it is
		// written HERE -- before javac runs -- because src/main/java has to be able to
		// compile against it.
		for (Map.Entry<String, byte[]> runtimeClass : compiled.get().runtimeClasses().entrySet()) {
			write(runtimeClass.getKey(), runtimeClass.getValue());
		}
		return Optional.of(className);
	}

	// Only writes what changed, so an unchanged runtime class does not get a new
	// timestamp on every build (and does not make the next build look stale).
	private void write(String path, byte[] bytes) throws IOException {
		Path target = this.outputDirectory.resolve(path);
		if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
			return;
		}
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(target, bytes);
	}

	private void writeStatus(List<String> status, long started) throws IOException {
		Path parent = this.statusFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(this.statusFile, status, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(this.statusFile, FileTime.fromMillis(started));
	}

}
