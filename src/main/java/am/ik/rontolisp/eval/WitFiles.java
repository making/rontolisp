package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.compiler.WitSource;
import am.ik.wit.WitSourceFile;
import org.jspecify.annotations.Nullable;

/**
 * Reads the WIT a {@code rontolisp:wit-import} / {@code rontolisp:wit-export} path names,
 * through a {@link SourceLoader}: a {@code .wit} file, or a package published as a
 * directory -- its {@code *.wit} files, and each entry of its {@code deps/} (a directory
 * of files or one {@code .wit} file) a package its {@code use} clauses may name. Entries
 * are read in name order, as {@code wasm-tools} reads them. A path {@code <jar>!/<entry>}
 * names a file or a directory inside a jar. Every directive and front end reads its WIT
 * here.
 */
public final class WitFiles {

	private static final String DEPS = "deps/";

	/** What parts a jar's path from an entry's path in it. */
	static final String ENTRY = "!/";

	private static final String EXTENSION = ".wit";

	private WitFiles() {
	}

	/**
	 * Reads the WIT at a resolved path.
	 * @param host the loader
	 * @param path the file or directory
	 * @return the source
	 * @throws IOException when the path cannot be read, or names a directory holding no
	 * {@code .wit} file
	 */
	public static WitSource read(SourceLoader host, String path) throws IOException {
		SourceLoader loader = path.contains(ENTRY) ? archives(host) : host;
		List<String> entries = loader.listDirectory(path);
		if (entries == null) {
			try {
				return WitSource.file(path, loader.load(path));
			}
			catch (IOException ex) {
				String parent = SourceLoader.parentDir(path);
				if (!path.endsWith(EXTENSION) && loader.listDirectory(parent == null ? "." : parent) == null) {
					throw new IOException(ex.getMessage()
							+ " (a WIT package directory is read only where the loader lists directories)", ex);
				}
				throw ex;
			}
		}
		List<WitSourceFile> files = files(loader, path, entries);
		if (files.isEmpty()) {
			throw new IOException("the directory holds no " + EXTENSION + " file");
		}
		List<List<WitSourceFile>> deps = new ArrayList<>();
		if (entries.contains(DEPS)) {
			String depsDir = SourceLoader.resolve(path, DEPS);
			List<String> depEntries = loader.listDirectory(depsDir);
			for (String entry : depEntries == null ? List.<String>of() : sorted(depEntries)) {
				if (entry.endsWith("/")) {
					String dir = SourceLoader.resolve(depsDir, entry);
					List<String> inner = loader.listDirectory(dir);
					List<WitSourceFile> dep = files(loader, dir, inner == null ? List.of() : inner);
					if (!dep.isEmpty()) {
						deps.add(dep);
					}
				}
				else if (entry.endsWith(EXTENSION)) {
					String file = SourceLoader.resolve(depsDir, entry);
					deps.add(List.of(new WitSourceFile(file, loader.load(file))));
				}
			}
		}
		return WitSource.directory(path, files, deps);
	}

	/**
	 * Whether a path names a file or a directory, a jar's entry included.
	 * @param host the loader
	 * @param path the file or directory
	 * @return {@code true} where {@link #read} finds something to read
	 */
	public static boolean exists(SourceLoader host, String path) {
		if (!path.contains(ENTRY)) {
			return host.exists(path) || host.listDirectory(path) != null;
		}
		int at = path.indexOf(ENTRY);
		List<String> listed = host.listArchive(path.substring(0, at));
		return listed != null
				&& (listed.contains(path.substring(at + ENTRY.length())) || archives(host).listDirectory(path) != null);
	}

	/**
	 * Where an entry of a jar is a file of its own, {@code <jar>!/<entry>}: a WIT beside
	 * a file a library's jar holds is read in the jar, a directory of it listed from the
	 * jar's entries. Any other path is the loader's.
	 */
	private static SourceLoader archives(SourceLoader loader) {
		return new SourceLoader() {
			@Override
			public String load(String path) throws IOException {
				int at = path.indexOf(ENTRY);
				return at < 0 ? loader.load(path)
						: loader.loadArchiveEntry(path.substring(0, at), path.substring(at + ENTRY.length()));
			}

			@Override
			public @Nullable List<String> listDirectory(String path) {
				int at = path.indexOf(ENTRY);
				if (at < 0) {
					return loader.listDirectory(path);
				}
				List<String> listed = loader.listArchive(path.substring(0, at));
				if (listed == null) {
					return null;
				}
				String entry = path.substring(at + ENTRY.length());
				String prefix = entry.isEmpty() || entry.endsWith("/") ? entry : entry + "/";
				// the names one level below, a directory's once: a jar need not hold
				// an entry of its own for a directory
				Set<String> names = new LinkedHashSet<>();
				for (String name : listed) {
					if (name.length() > prefix.length() && name.startsWith(prefix)) {
						int slash = name.indexOf('/', prefix.length());
						names.add(name.substring(prefix.length(), slash < 0 ? name.length() : slash + 1));
					}
				}
				return names.isEmpty() && !listed.contains(prefix) ? null : List.copyOf(names);
			}
		};
	}

	// A directory's .wit files, in name order.
	private static List<WitSourceFile> files(SourceLoader loader, String dir, List<String> entries) throws IOException {
		List<WitSourceFile> files = new ArrayList<>();
		for (String entry : sorted(entries)) {
			if (entry.endsWith(EXTENSION)) {
				String file = SourceLoader.resolve(dir, entry);
				files.add(new WitSourceFile(file, loader.load(file)));
			}
		}
		return files;
	}

	private static List<String> sorted(List<String> entries) {
		List<String> copy = new ArrayList<>(entries);
		copy.sort(null);
		return copy;
	}

}
