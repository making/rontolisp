package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.JvmSourceCompiler;

/**
 * One directory of {@code .lisp} sources compiled into one directory of {@code .class}
 * files -- the whole of what the {@code compile} goal does, with no Maven type in sight
 * so that it can be driven straight from a test.
 * <p>
 * <strong>The path IS the class name.</strong>
 * {@code src/main/lisp/com/acme/Kernels.lisp} becomes {@code com.acme.Kernels}, the
 * convention every JVM-language plugin uses, so no per-file declaration is needed and the
 * output lands where {@code maven-jar-plugin} already looks.
 */
public final class LispSourceSet {

	/** The extension a source file is recognized by. */
	public static final String EXTENSION = ".lisp";

	private final Path sourceDirectory;

	private final Path outputDirectory;

	private final Consumer<JvmSourceCompiler> configuration;

	/**
	 * @param sourceDirectory the directory holding the {@code .lisp} tree
	 * @param outputDirectory where the {@code .class} files go
	 * @param configuration applies the build's flags to each file's compiler
	 */
	public LispSourceSet(Path sourceDirectory, Path outputDirectory, Consumer<JvmSourceCompiler> configuration) {
		this.sourceDirectory = sourceDirectory;
		this.outputDirectory = outputDirectory;
		this.configuration = configuration;
	}

	/**
	 * What a compilation did.
	 *
	 * @param sources how many {@code .lisp} files the source directory holds
	 * @param classes the binary names of the classes written, in source order
	 * @param upToDate whether nothing was stale, so nothing was compiled
	 */
	public record Result(int sources, List<String> classes, boolean upToDate) {
	}

	/**
	 * Compiles the source set, skipping the work entirely when every class is newer than
	 * every source.
	 * <p>
	 * Staleness is all-or-nothing, which is {@code maven-compiler-plugin}'s own rule and
	 * the only safe one here: a {@code (load "...")} splices one source into another, so
	 * a file whose own timestamp did not move can still need recompiling.
	 * @return what was compiled
	 * @throws IOException if the tree cannot be read or the classes cannot be written
	 */
	public Result compile() throws IOException {
		List<Path> sources = sources();
		if (sources.isEmpty()) {
			return new Result(0, List.of(), true);
		}
		List<String> classes = new ArrayList<>();
		for (Path source : sources) {
			classes.add(className(source));
		}
		if (upToDate(sources, classes)) {
			return new Result(sources.size(), List.copyOf(classes), true);
		}
		for (int i = 0; i < sources.size(); i++) {
			compileOne(sources.get(i), classes.get(i));
		}
		return new Result(sources.size(), List.copyOf(classes), false);
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
	 * @param source a file under the source directory
	 * @return the binary class name
	 */
	public String className(Path source) {
		Path relative = this.sourceDirectory.toAbsolutePath().relativize(source.toAbsolutePath());
		String name = relative.toString().replace(File.separatorChar, '/');
		name = name.substring(0, name.length() - EXTENSION.length()).replace('/', '.');
		for (String segment : name.split("\\.", -1)) {
			if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))
					|| !segment.chars().skip(1).allMatch(Character::isJavaIdentifierPart)) {
				throw new IllegalArgumentException(source + ": '" + segment + "' is not a Java identifier, so "
						+ relative + " does not name a class. The path under the source directory IS the class name"
						+ " (com/acme/Kernels" + EXTENSION + " -> com.acme.Kernels), so rename it");
			}
		}
		return name;
	}

	private boolean upToDate(List<Path> sources, List<String> classes) throws IOException {
		long newestSource = 0;
		for (Path source : sources) {
			newestSource = Math.max(newestSource, Files.getLastModifiedTime(source).toMillis());
		}
		for (String className : classes) {
			Path target = this.outputDirectory.resolve(className.replace('.', '/') + ".class");
			if (!Files.isRegularFile(target) || Files.getLastModifiedTime(target).toMillis() < newestSource) {
				return false;
			}
		}
		return true;
	}

	private void compileOne(Path source, String className) throws IOException {
		JvmSourceCompiler compiler = new JvmSourceCompiler(className);
		// A (load "sibling.lisp") resolves against the file that wrote it, as it does on
		// the command line.
		Path parent = source.toAbsolutePath().getParent();
		compiler.baseDir(parent == null ? null : parent.toString());
		this.configuration.accept(compiler);
		JvmSourceCompiler.Result compiled;
		try {
			compiled = compiler.compile(Files.readString(source, StandardCharsets.UTF_8), source.toString());
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
		catch (RuntimeException ex) {
			throw new LispCompilationException(source, ex);
		}
		write(compiled.internalClassName() + ".class", compiled.classBytes());
		// A :float-vector / :float-matrix export hands out a handle class. It travels at
		// its canonical name so two rontolisp libraries agree on the type, and it is
		// written HERE -- before javac runs -- because src/main/java has to compile
		// against it.
		for (Map.Entry<String, byte[]> runtimeClass : compiled.runtimeClasses().entrySet()) {
			write(runtimeClass.getKey(), runtimeClass.getValue());
		}
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

}
