package am.ik.rontolisp.eval;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The files uploaded to the browser playground, which has no file system, as the
 * directory a program runs in: a file is read by the name it was uploaded under
 * ({@code geo/Place.java}), and the names' slashes make the directories it lists, so
 * {@code directory}, {@code probe-file}, a Clojure root and a Java program's resources
 * see the same files {@code load} reads. The top is the empty name, {@code .} or
 * {@code ./}; a directory holding no upload is none.
 */
public final class UploadedFiles implements SourceLoader {

	private final Map<String, String> files;

	/**
	 * The uploads of a map, read where they are asked for, so a file put into it later is
	 * found.
	 * @param files the texts by the names they were uploaded under
	 */
	public UploadedFiles(Map<String, String> files) {
		this.files = files;
	}

	@Override
	public String load(String path) throws FileNotFoundException {
		String text = this.files.get(path);
		if (text == null) {
			throw new FileNotFoundException(
					path + " (upload it first; available: " + String.join(", ", this.files.keySet()) + ")");
		}
		return text;
	}

	@Override
	public boolean exists(String path) {
		return this.files.containsKey(path) || listDirectory(path) != null;
	}

	@Override
	public @Nullable List<String> listDirectory(String path) {
		String directory = path.startsWith("./") ? path.substring(2) : path.equals(".") ? "" : path;
		if (!directory.isEmpty() && !directory.endsWith("/")) {
			directory += "/";
		}
		Set<String> entries = new LinkedHashSet<>();
		for (String name : this.files.keySet()) {
			if (name.startsWith(directory) && name.length() > directory.length()) {
				int slash = name.indexOf('/', directory.length());
				entries.add(
						slash < 0 ? name.substring(directory.length()) : name.substring(directory.length(), slash + 1));
			}
		}
		return entries.isEmpty() && !directory.isEmpty() ? null : new ArrayList<>(entries);
	}

}
