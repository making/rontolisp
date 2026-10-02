package am.ik.rontolisp.clojure;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Files held in memory, by {@code /}-separated relative path: what a lowering test hands
 * {@link Clojure#read} so a {@code require} finds a namespace without a disk. The empty
 * path is the working directory, so a {@code deps.edn} search walks up to it.
 */
final class MemoryClojureFiles implements ClojureFiles {

	private final Map<String, String> files;

	MemoryClojureFiles(Map<String, String> files) {
		this.files = files;
	}

	@Override
	public @Nullable String read(String path) {
		return this.files.get(path);
	}

	@Override
	public @Nullable String parent(String path) {
		if (path.isEmpty()) {
			return null;
		}
		int cut = path.lastIndexOf('/');
		return cut < 0 ? "" : path.substring(0, cut);
	}

	@Override
	public String resolve(@Nullable String dir, String relative) {
		return dir == null || dir.isEmpty() ? relative : dir + "/" + relative;
	}

}
