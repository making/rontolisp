package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.WasmSourceCompiler;

/**
 * One program of a source tree compiled to one WebAssembly output -- the whole of what
 * the {@code wasm} goal does, with no Maven type in sight.
 * <p>
 * The program starts at its main class's file, {@code app/Main.java} for
 * {@code app.Main}, and the front end reads every other class it names from the same
 * tree, as {@code rontolisp app/Main.java -o out.wasm} does, and a library's from the
 * source path's directories and jars, as {@code --java-source-path} does. Which files
 * that is, only the compile knows, so staleness is all-or-nothing over every
 * {@code .java} file of the tree and of a directory on the source path, and every jar on
 * it: the output is rebuilt when any of them is newer than the last build, when one is
 * added or removed, when the configuration changed, or when the output is gone.
 */
public final class WasmProgram {

	/** The extension of the files the program is read from. */
	public static final String EXTENSION = ".java";

	private final Path sourceDirectory;

	private final String mainClass;

	private final Path output;

	private final Path statusFile;

	private final List<Path> sourcePath;

	private final String configuration;

	private final Consumer<WasmSourceCompiler> configure;

	/**
	 * @param sourceDirectory the root of the source tree
	 * @param mainClass the binary name of the class the program starts at
	 * @param output the {@code .wasm} file, or under {@code --native} the executable
	 * @param statusFile where the last build's configuration and sources are recorded
	 * @param sourcePath the directories and jars the program's libraries are read from,
	 * in the order they are searched
	 * @param configuration the flags the build compiles with, as text: a change rebuilds
	 * @param configure applies those flags to the compiler
	 */
	public WasmProgram(Path sourceDirectory, String mainClass, Path output, Path statusFile, List<Path> sourcePath,
			String configuration, Consumer<WasmSourceCompiler> configure) {
		this.sourceDirectory = sourceDirectory;
		this.mainClass = mainClass;
		this.output = output;
		this.statusFile = statusFile;
		this.sourcePath = List.copyOf(sourcePath);
		this.configuration = configuration;
		this.configure = configure;
	}

	/**
	 * Compiles the program unless nothing changed since the last build.
	 * @return whether it compiled ({@code false}: the output is up to date)
	 * @throws IOException if the tree cannot be read or the output cannot be written
	 * @throws IllegalArgumentException if the main class names no file of the tree, or an
	 * entry of the source path is no directory or file
	 */
	public boolean compile() throws IOException {
		// Stamped BEFORE the work: a source edited while the compile runs is newer than
		// the stamp, and the next build compiles again.
		long started = System.currentTimeMillis();
		Path entry = entry();
		List<Path> sources = new ArrayList<>(sources(this.sourceDirectory));
		List<String> status = new ArrayList<>();
		status.add(this.configuration);
		for (Path source : sources) {
			status.add(relative(this.sourceDirectory, source));
		}
		List<String> roots = new ArrayList<>();
		for (Path root : this.sourcePath) {
			String absolute = root.toAbsolutePath().normalize().toString();
			roots.add(absolute);
			status.add("sourcePath " + absolute);
			if (Files.isDirectory(root)) {
				for (Path source : sources(root)) {
					sources.add(source);
					status.add("  " + relative(root, source));
				}
			}
			else if (Files.isRegularFile(root)) {
				sources.add(root);
			}
			else {
				throw new IllegalArgumentException("javaSourcePath: no such directory or jar: " + root);
			}
		}
		if (upToDate(sources, status)) {
			return false;
		}
		WasmSourceCompiler compiler = new WasmSourceCompiler();
		Path parent = entry.toAbsolutePath().getParent();
		compiler.baseDir(parent == null ? null : parent.toString());
		compiler.javaSourcePath(roots);
		this.configure.accept(compiler);
		try {
			compiler.compile(Files.readString(entry, StandardCharsets.UTF_8), entry.toString()).write(this.output);
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
		Path statusParent = this.statusFile.getParent();
		if (statusParent != null) {
			Files.createDirectories(statusParent);
		}
		Files.write(this.statusFile, status, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(this.statusFile, FileTime.fromMillis(started));
		return true;
	}

	/**
	 * @return the main class's file
	 * @throws IllegalArgumentException if there is none
	 */
	public Path entry() {
		Path entry = this.sourceDirectory.resolve(this.mainClass.replace('.', '/') + EXTENSION);
		if (!Files.isRegularFile(entry)) {
			throw new IllegalArgumentException("mainClass " + this.mainClass + " names " + entry
					+ ", which does not exist: the program starts at the file its main class's package and name"
					+ " spell under " + this.sourceDirectory);
		}
		return entry;
	}

	private static List<Path> sources(Path directory) throws IOException {
		try (Stream<Path> tree = Files.walk(directory)) {
			return tree.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
				.sorted(Comparator.comparing(Path::toString))
				.toList();
		}
	}

	private boolean upToDate(List<Path> sources, List<String> status) throws IOException {
		if (!Files.isRegularFile(this.statusFile) || !Files.isRegularFile(this.output)
				|| !Files.readAllLines(this.statusFile, StandardCharsets.UTF_8).equals(status)) {
			return false;
		}
		long stamp = Files.getLastModifiedTime(this.statusFile).toMillis();
		for (Path source : sources) {
			if (Files.getLastModifiedTime(source).toMillis() > stamp) {
				return false;
			}
		}
		return true;
	}

	private static String relative(Path directory, Path source) {
		return directory.toAbsolutePath()
			.relativize(source.toAbsolutePath())
			.toString()
			.replace(File.separatorChar, '/');
	}

}
