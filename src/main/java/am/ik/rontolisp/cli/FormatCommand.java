package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import am.ik.rontolisp.format.LispFormatter;

/**
 * The {@code rontolisp format} subcommand: re-indents Lisp source files in place.
 * <p>
 * A directory argument is walked for {@code .lisp} and {@code .asd} files; a file named
 * explicitly is formatted whatever its extension, so a {@code .cl} or a script can be
 * passed by name. Files are visited in sorted order and each is written only when its
 * content actually changes, so the command is safe to re-run and leaves timestamps alone
 * on an already-formatted tree.
 */
final class FormatCommand {

	/** Extensions searched for inside a directory argument. */
	private static final List<String> EXTENSIONS = List.of(".lisp", ".asd");

	private final PrintStream out;

	private final InputStream in;

	/**
	 * Create the command.
	 * @param in the stream a {@code -} argument reads from
	 * @param out the stream messages and {@code --stdout} output go to
	 */
	FormatCommand(InputStream in, PrintStream out) {
		this.in = in;
		this.out = out;
	}

	/**
	 * Run the command.
	 * @param args the arguments after the {@code format} subcommand name
	 * @return the process exit code: 0 when nothing needed changing (or everything was
	 * written), 1 when {@code --check} found a file that is not formatted, 2 on an error
	 */
	int run(String[] args) {
		boolean check = false;
		boolean toStdout = false;
		int width = LispFormatter.DEFAULT_WIDTH;
		List<String> paths = new ArrayList<>();
		for (String arg : args) {
			switch (arg) {
				case "-h", "--help" -> {
					printUsage();
					return 0;
				}
				case "--check" -> check = true;
				case "--stdout" -> toStdout = true;
				default -> {
					if (arg.startsWith("--width=")) {
						try {
							width = Integer.parseInt(arg.substring("--width=".length()));
						}
						catch (NumberFormatException _) {
							return fail("--width needs a number, got '" + arg.substring("--width=".length()) + "'");
						}
						if (width < 20) {
							return fail("--width must be at least 20");
						}
					}
					else if (arg.startsWith("-") && !"-".equals(arg)) {
						return fail("unknown option '" + arg + "' (try: rontolisp format --help)");
					}
					else {
						paths.add(arg);
					}
				}
			}
		}
		if (paths.isEmpty()) {
			return fail("no file or directory given (try: rontolisp format --help)");
		}
		// Reading from stdin has no file to write back to, so it always prints.
		if (paths.size() == 1 && "-".equals(paths.get(0))) {
			return formatStdin(width);
		}
		List<Path> files;
		try {
			files = collect(paths);
		}
		catch (IOException ex) {
			return fail(message(ex));
		}
		if (files.isEmpty()) {
			return fail("no " + String.join("/", EXTENSIONS) + " files found");
		}
		if (toStdout && files.size() > 1) {
			return fail("--stdout takes a single file, got " + files.size());
		}
		return formatFiles(files, width, check, toStdout);
	}

	private int formatFiles(List<Path> files, int width, boolean check, boolean toStdout) {
		int exit = 0;
		int changed = 0;
		for (Path file : files) {
			String source;
			String formatted;
			try {
				source = Files.readString(file);
				formatted = LispFormatter.format(source, width);
			}
			catch (IOException | RuntimeException ex) {
				// One unreadable file must not abandon the rest of the tree: report
				// it, keep going, and fail the whole run at the end.
				warn(file + ": " + message(ex));
				exit = 2;
				continue;
			}
			if (toStdout) {
				this.out.print(formatted);
				continue;
			}
			if (formatted.equals(source)) {
				continue;
			}
			changed++;
			if (check) {
				this.out.println(file);
				continue;
			}
			try {
				Files.writeString(file, formatted);
			}
			catch (IOException ex) {
				warn(file + ": " + message(ex));
				exit = 2;
				continue;
			}
			this.out.println("formatted " + file);
		}
		if (check && changed > 0) {
			this.out.println(changed + " of " + files.size() + " file(s) need formatting");
			return exit != 0 ? exit : 1;
		}
		return exit;
	}

	private int formatStdin(int width) {
		try {
			this.out.print(LispFormatter.format(new String(this.in.readAllBytes(), StandardCharsets.UTF_8), width));
			return 0;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			return fail("<stdin>: " + message(ex));
		}
	}

	private static List<Path> collect(List<String> paths) throws IOException {
		List<Path> files = new ArrayList<>();
		for (String path : paths) {
			Path start = Path.of(path);
			if (Files.isRegularFile(start)) {
				// Named explicitly, so the extension is the caller's business, not ours.
				files.add(start);
				continue;
			}
			if (!Files.isDirectory(start)) {
				throw new IOException("no such file or directory: " + path);
			}
			try (Stream<Path> walk = Files.walk(start)) {
				walk.filter(Files::isRegularFile).filter(FormatCommand::hasLispExtension).forEach(files::add);
			}
		}
		return files.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
	}

	private static boolean hasLispExtension(Path file) {
		String name = file.getFileName().toString();
		return EXTENSIONS.stream().anyMatch(name::endsWith);
	}

	private static String message(Exception ex) {
		String message = ex.getMessage();
		return message != null ? message : ex.toString();
	}

	private int fail(String message) {
		warn(message);
		return 2;
	}

	private static void warn(String message) {
		System.err.println("rontolisp: format: " + message);
	}

	private void printUsage() {
		this.out.println("Usage: rontolisp format [options] <file-or-directory>...");
		this.out.println();
		this.out.println("Re-indents Lisp source in place. A directory is walked for");
		this.out.println(".lisp and .asd files; a file named explicitly is formatted");
		this.out.println("whatever its extension. '-' formats standard input to stdout.");
		this.out.println();
		this.out.println("Only whitespace changes: indentation, line breaks and blank");
		this.out.println("lines. Token spelling (case included), strings, block comments");
		this.out.println("and #+/#- guards are reproduced verbatim, so the formatted file");
		this.out.println("reads as exactly the same program.");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  --check            Do not write; list the files that are not");
		this.out.println("                     formatted and exit 1 if there are any");
		this.out.println("  --stdout           Write the result to stdout instead of the");
		this.out.println("                     file (a single file only)");
		this.out.println("  --width=N          Right margin to wrap to (default " + LispFormatter.DEFAULT_WIDTH + ")");
		this.out.println("  -h, --help         Show this help message");
	}

}
