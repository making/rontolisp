package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.compiler.WitSource;
import am.ik.wit.WitSourceFile;

/**
 * Reads the WIT a {@code rontolisp:wit-import} / {@code rontolisp:wit-export} path names,
 * through a {@link SourceLoader}: a {@code .wit} file, or a package published as a
 * directory -- its {@code *.wit} files, and each entry of its {@code deps/} (a directory
 * of files or one {@code .wit} file) a package its {@code use} clauses may name. Entries
 * are read in name order, as {@code wasm-tools} reads them. Every directive and front end
 * reads its WIT here.
 */
public final class WitFiles {

	private static final String DEPS = "deps/";

	private static final String EXTENSION = ".wit";

	private WitFiles() {
	}

	/**
	 * Reads the WIT at a resolved path.
	 * @param loader the loader
	 * @param path the file or directory
	 * @return the source
	 * @throws IOException when the path cannot be read, or names a directory holding no
	 * {@code .wit} file
	 */
	public static WitSource read(SourceLoader loader, String path) throws IOException {
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
