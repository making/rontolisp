package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Where a project namespace's file is found: the source roots of the program a lowering
 * reads, and the file a namespace name maps to below them. The roots are, in order, the
 * directory the entry file's own namespace names (its own directory for a file without
 * one, the working directory for a read without a file), then the {@code :paths} of the
 * nearest {@code deps.edn} at or above the entry file's directory, resolved against that
 * file's directory ({@code ["src"]} when it names none) -- or, with no {@code deps.edn}
 * anywhere, {@code src} under the working directory, the oracle's default. Computed on
 * the first project {@code require}, so a program that loads nothing never looks.
 */
final class ClojureSourcePath {

	/**
	 * One namespace file found.
	 *
	 * @param path the path it was read from, for positions
	 * @param text its contents
	 * @param builtin whether it is a built-in namespace's file
	 * ({@link ClojureBuiltinNamespaces}) rather than the project's
	 */
	record Found(String path, String text, boolean builtin) {
	}

	private final ClojureFiles files;

	private final @Nullable String entryFile;

	private @Nullable String entryNs;

	private @Nullable List<String> roots;

	ClojureSourcePath(ClojureFiles files, @Nullable String entryFile) {
		this.files = files;
		this.entryFile = entryFile;
	}

	/**
	 * The namespace the entry file declares first, which names the root it sits under.
	 * @param ns the namespace, or {@code null} for a file without one
	 */
	void entryNamespace(@Nullable String ns) {
		this.entryNs = ns;
	}

	/**
	 * The entry file's path as the oracle's {@code *file*} holds it: absolute where the
	 * host has a working directory, {@code NO_SOURCE_PATH} for a read without a file.
	 * @return the path
	 */
	String entryPath() {
		if (this.entryFile == null) {
			return ClojureCoreSpecials.NO_SOURCE_PATH;
		}
		String dir = this.files.parent(this.entryFile);
		return dir == null ? this.entryFile : this.files.resolve(dir, lastSegmentOf(this.entryFile));
	}

	/**
	 * The entry file's name, the oracle's {@code *source-path*}, or
	 * {@code NO_SOURCE_FILE} for a read without a file.
	 * @return the name
	 */
	String entryName() {
		return this.entryFile == null ? ClojureCoreSpecials.NO_SOURCE_FILE : lastSegmentOf(this.entryFile);
	}

	/**
	 * The file a namespace maps to below a root, like the oracle's root resource: the
	 * dots are directories and a dash is an underscore ({@code my-app.core} is
	 * {@code my_app/core.clj}).
	 * @param ns the namespace
	 * @return the relative path
	 */
	static String resourceOf(String ns) {
		return ns.replace('-', '_').replace('.', '/') + ".clj";
	}

	/**
	 * The file of a namespace, from the first root holding it, else the built-in one
	 * ({@link ClojureBuiltinNamespaces}): a project file shadows a built-in namespace, as
	 * a source directory precedes a dependency jar on the oracle's classpath.
	 * @param ns the namespace
	 * @return the file, or {@code null} when neither a root nor the built-ins hold one
	 */
	@Nullable Found find(String ns) {
		String relative = resourceOf(ns);
		for (String root : roots()) {
			String path = this.files.resolve(root, relative);
			String text = this.files.read(path);
			if (text != null) {
				return new Found(path, text, false);
			}
		}
		String builtin = ClojureBuiltinNamespaces.source(ns);
		return builtin == null ? null : new Found(relative, builtin, true);
	}

	/**
	 * The roots as a refusal names them: {@code ": src, test"} (the working directory as
	 * {@code .}), or why there are none to search.
	 * @return the text, starting with its separator
	 */
	String describeRoots() {
		if (this.files == ClojureFiles.NONE) {
			return " (the program was read without files)";
		}
		List<String> shown = new ArrayList<>();
		for (String root : roots()) {
			shown.add(root.isEmpty() ? "." : root);
		}
		return ": " + String.join(", ", shown);
	}

	/**
	 * The source roots, in search order; an empty string is the working directory.
	 * @return the roots
	 */
	List<String> roots() {
		List<String> known = this.roots;
		if (known == null) {
			known = computeRoots();
			this.roots = known;
		}
		return known;
	}

	private List<String> computeRoots() {
		Set<String> out = new LinkedHashSet<>();
		String dir = this.entryFile == null ? "" : this.files.parent(this.entryFile);
		if (dir == null) {
			dir = "";
		}
		out.add(inferredRoot(dir));
		for (String probe = dir; probe != null; probe = this.entryFile == null ? null : this.files.parent(probe)) {
			String depsFile = this.files.resolve(probe, "deps.edn");
			String deps = this.files.read(depsFile);
			if (deps != null) {
				for (String path : depsPaths(deps, depsFile)) {
					out.add(this.files.resolve(probe, path));
				}
				return List.copyOf(out);
			}
		}
		// no deps.edn anywhere: the oracle's default, src under the working directory
		out.add(this.files.resolve(null, "src"));
		return List.copyOf(out);
	}

	/**
	 * The root the entry file's namespace names: {@code src} for
	 * {@code src/demo/main.clj} declaring {@code demo.main}. When the file's name and the
	 * directories above it do not spell the namespace (or there is none), the file's own
	 * directory.
	 */
	private String inferredRoot(String dir) {
		String file = this.entryFile;
		String ns = this.entryNs;
		if (file == null || ns == null) {
			return dir;
		}
		String[] segments = ns.split("\\.", -1);
		if (segments.length < 2 || !lastSegmentOf(file).equals(munge(segments[segments.length - 1]) + ".clj")) {
			return dir;
		}
		String root = dir;
		for (int i = segments.length - 2; i >= 0; i--) {
			if (root == null || !lastSegmentOf(root).equals(munge(segments[i]))) {
				return dir;
			}
			root = this.files.parent(root);
		}
		return root == null ? dir : root;
	}

	private static String munge(String segment) {
		return segment.replace('-', '_');
	}

	static String lastSegmentOf(String path) {
		int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return path.substring(cut + 1);
	}

	/**
	 * The {@code :paths} strings of a {@code deps.edn}, read as EDN by the Clojure
	 * reader; {@code ["src"]} when the map names none (the oracle's default) or the file
	 * does not read as a map. An alias keyword among the paths is skipped (aliases are
	 * opt-in on the oracle's command line).
	 */
	static List<String> depsPaths(String text, String path) {
		List<LispVal> datums;
		try {
			datums = new ClojureReader(text, path).readAll();
		}
		catch (LispReadException ex) {
			return List.of("src");
		}
		if (datums.isEmpty()) {
			return List.of("src");
		}
		List<LispVal> map = ClojureLowerUtil.items(datums.get(0));
		if (map == null || map.isEmpty() || !ClojureLowerUtil.isSymbolNamed(map.get(0), "%hash-map")) {
			return List.of("src");
		}
		for (int i = 1; i + 1 < map.size(); i += 2) {
			if (ClojureLowerUtil.isSymbolNamed(map.get(i), ":paths")) {
				List<LispVal> paths = ClojureLowerUtil.items(map.get(i + 1));
				List<String> out = new ArrayList<>();
				if (paths != null) {
					for (LispVal element : paths) {
						if (element instanceof LispString root) {
							out.add(root.value());
						}
					}
				}
				return out;
			}
		}
		return List.of("src");
	}

}
