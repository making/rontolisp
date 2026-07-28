package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
	 * Returns a loader that reads files from the local filesystem.
	 * @return a filesystem-backed loader
	 */
	static SourceLoader fileSystem() {
		return new SourceLoader() {
			@Override
			public String load(String path) throws IOException {
				return Files.readString(Path.of(path));
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
