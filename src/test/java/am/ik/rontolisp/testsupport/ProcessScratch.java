package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The per-process scratch root under {@code <tmpdir>/rontolisp-wasmtime} that the host
 * wasmtime runs stage their files in: {@code p<PID>}, so two JVMs on one machine never
 * share a file (.kb/test-execution.md).
 *
 * <p>
 * It is deleted when the JVM exits, and a directory a KILLED JVM left behind is swept the
 * next time any JVM creates its root: before this, nothing deleted them, and 10,150 of
 * them (6.7 GB) filled the development machine's disk.
 */
public final class ProcessScratch {

	private static final Path BASE = Path.of(System.getProperty("java.io.tmpdir"), "rontolisp-wasmtime");

	// "p<PID>" and the "p<PID>-<suffix>" per-thread directories older builds created
	// beside it.
	private static final Pattern OWNED = Pattern.compile("p(\\d+)(?:-.*)?");

	private static final Path ROOT = create(BASE);

	private ProcessScratch() {
	}

	/**
	 * Returns this JVM's scratch root, created on first use.
	 * @return the directory
	 */
	public static Path root() {
		return ROOT;
	}

	static Path create(Path base) {
		sweep(base);
		Path root = base.resolve("p" + ProcessHandle.current().pid());
		try {
			Files.createDirectories(root);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot create the scratch root " + root, ex);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(root), "scratch-cleanup"));
		return root;
	}

	/** Deletes every directory under {@code base} owned by a process that is gone. */
	static void sweep(Path base) {
		if (!Files.isDirectory(base)) {
			return;
		}
		try (Stream<Path> entries = Files.list(base)) {
			for (Path entry : entries.toList()) {
				Matcher owned = OWNED.matcher(entry.getFileName().toString());
				if (owned.matches() && ProcessHandle.of(Long.parseLong(owned.group(1))).isEmpty()) {
					deleteTree(entry);
				}
			}
		}
		catch (IOException ex) {
			// Another JVM sweeping the same base at once; whatever is left is the next
			// sweep's.
		}
	}

	private static void deleteTree(Path root) {
		try (Stream<Path> tree = Files.walk(root)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
		catch (IOException | UncheckedIOException ex) {
			// Best effort: a concurrent sweep may have removed part of it already.
		}
	}

}
