package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Files held in memory, by {@code /}-separated relative path: what a lowering test hands
 * {@link Clojure#read} so a {@code require} finds a namespace without a disk. The empty
 * path is the working directory, so a {@code deps.edn} search walks up to it. A directory
 * is any path some file sits below; a jar is a map of entry names to their text; a path
 * is canonical with its {@code .} and {@code ..} segments folded.
 */
final class MemoryClojureFiles implements ClojureFiles {

	private final Map<String, String> files;

	private final Map<String, Map<String, String>> archives;

	private final @Nullable String userConfigDir;

	MemoryClojureFiles(Map<String, String> files) {
		this(files, Map.of(), null);
	}

	MemoryClojureFiles(Map<String, String> files, Map<String, Map<String, String>> archives,
			@Nullable String userConfigDir) {
		this.files = files;
		this.archives = archives;
		this.userConfigDir = userConfigDir;
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

	@Override
	public boolean exists(String path) {
		return this.files.containsKey(path) || this.archives.containsKey(path) || isDirectory(path);
	}

	@Override
	public boolean isDirectory(String path) {
		String prefix = path.isEmpty() ? "" : path + "/";
		return this.files.keySet().stream().anyMatch(name -> name.startsWith(prefix))
				|| this.archives.keySet().stream().anyMatch(name -> name.startsWith(prefix));
	}

	@Override
	public String canonical(String path) {
		Deque<String> segments = new ArrayDeque<>();
		for (String segment : path.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..") && !segments.isEmpty() && !segments.peekLast().equals("..")) {
				segments.removeLast();
			}
			else {
				segments.addLast(segment);
			}
		}
		return String.join("/", segments);
	}

	@Override
	public @Nullable List<String> archiveEntries(String path) {
		Map<String, String> entries = this.archives.get(path);
		return entries == null ? null : new ArrayList<>(entries.keySet());
	}

	@Override
	public @Nullable String readArchiveEntry(String archive, String entry) {
		Map<String, String> entries = this.archives.get(archive);
		return entries == null ? null : entries.get(entry);
	}

	@Override
	public @Nullable String userConfigDir() {
		return this.userConfigDir;
	}

}
