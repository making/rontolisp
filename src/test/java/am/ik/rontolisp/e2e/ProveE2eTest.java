package am.ik.rontolisp.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prove's own test system through {@code asdf:test-system} ({@code .kb/asdf.md}, "Classes
 * loaded code defines"): {@code prove-test.asd} names a {@code :test-file} component, a
 * class only {@code prove-asdf}'s source defines, and its {@code :defsystem-depends-on}
 * is what brings the class in. The class's methods decide what the component does: its
 * {@code load-op} remembers the file, and the file runs when the {@code test-op} body
 * asks, on every backend. Prove (MIT, quicklisp dist prove-20200218-git) and the
 * dependencies no other fixture holds -- cl-ansi-text (LLGPL), cl-colors and cl-colors2
 * (Boost), let-plus (Boost), anaphora (public domain) -- are vendored unmodified, sources
 * only. {@code prove-test.expected} is what SBCL 2.2.9 with ASDF 3.3.1 prints, normalized
 * as {@link #normalizeLine} normalizes a backend's output.
 */
class ProveE2eTest extends AsdfLibraryE2eSupport {

	private static Path resource(String name) {
		return Path.of("src", "test", "resources", name).toAbsolutePath();
	}

	@Override
	protected String systemDir() {
		return resource("prove").toString();
	}

	@Override
	protected List<String> extraSystemPath() {
		return List
			.of("alexandria", "anaphora", "cl-ansi-text", "cl-colors", "cl-colors2", "cl-ppcre", "let-plus",
					"parse-number", "split-sequence")
			.stream()
			.map(dir -> resource(dir).toString())
			.toList();
	}

	@Override
	protected String exercise() {
		return "(asdf:test-system :prove-test)";
	}

	@Override
	protected List<String> expected() {
		try {
			return Files.readAllLines(resource("prove-test.expected"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Takes out what differs between two runs or two implementations and says nothing of
	 * the behavior: the reporter's colors and durations, where the test file lives, and
	 * how a condition object prints beyond its class.
	 */
	@Override
	protected String normalizeLine(String line) {
		// A line reaches here trimmed, its leading ESC with it.
		return line.replaceAll("\u001b?\\[[0-9;]*m", "")
			.replaceAll(" ?\\(\\d+ms\\)", "")
			.stripTrailing()
			.replaceFirst("Running a test file '.*/t/", "Running a test file 't/")
			.replaceFirst("#<SIMPLE-ERROR [^>]*>", "#<SIMPLE-ERROR>");
	}

	@Override
	protected String artifactName() {
		return "ProveTestSystem";
	}

}
