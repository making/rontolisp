package am.ik.servlet.httpserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.Servlet;
import org.jspecify.annotations.Nullable;

/**
 * The files of a directory the server is given: the application's document root (Servlet
 * 6.1, 10.5), whose resources are looked for ahead of the class path's.
 */
final class DocumentRoot {

	private final Path root;

	DocumentRoot(Path root) {
		this.root = root.toAbsolutePath();
	}

	/** The default servlet, made where there is a document root. */
	Servlet servlet(WebContext context) {
		return new DefaultServlet(context);
	}

	/**
	 * The file or the directory of a path.
	 * @param path a normalized path starting with a slash
	 * @return what is there, or null for nothing
	 */
	@Nullable Resource find(String path) {
		Path file = file(path);
		if (file == null) {
			return null;
		}
		if (Files.isDirectory(file)) {
			return new Found(file, true, 0, 0);
		}
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return new Found(file, false, Files.size(file), file.toFile().lastModified());
		}
		catch (IOException ex) {
			// gone since it was looked at
			return null;
		}
	}

	/** The URL of the file or the directory of a path, or null. */
	@Nullable URL url(String path) throws MalformedURLException {
		Path file = file(path);
		if (file == null || !Files.exists(file)) {
			return null;
		}
		if (System.getProperty("java.class.path") != null) {
			return file.toUri().toURL();
		}
		// A program compiled ahead of time has no URI of a path: the file's own path.
		return new URL("file", "", file + (Files.isDirectory(file) ? "/" : ""));
	}

	/** The octets of the file of a path, or null where it is no file. */
	@Nullable InputStream stream(String path) throws IOException {
		Path file = file(path);
		return file != null && Files.isRegularFile(file) ? Files.newInputStream(file) : null;
	}

	/**
	 * The names of the entries of a directory, a directory's with a slash.
	 * @param directory a normalized path ending with a slash
	 * @return the names, or null where the path is no directory
	 */
	@Nullable List<String> names(String directory) throws IOException {
		Path dir = file(directory);
		if (dir == null) {
			return null;
		}
		List<String> names = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
			for (Path entry : entries) {
				names.add(entry.getFileName() + (Files.isDirectory(entry) ? "/" : ""));
			}
		}
		return names;
	}

	/**
	 * The path of the file system a path names, whether there is a file or not.
	 * @param path a normalized path starting with a slash
	 */
	String realPath(String path) {
		String real = place(path).toString();
		return path.endsWith("/") && !real.endsWith(File.separator) ? real + File.separator : real;
	}

	/** What is at a path: null for a path with a last slash that is no directory. */
	private @Nullable Path file(String path) {
		Path file = place(path);
		return path.endsWith("/") && !Files.isDirectory(file) ? null : file;
	}

	private Path place(String path) {
		return path.length() == 1 ? this.root : this.root.resolve(path.substring(1));
	}

	/** A file or a directory of the document root. */
	static final class Found extends Resource {

		private final Path file;

		Found(Path file, boolean directory, long length, long modified) {
			super(directory, length, modified);
			this.file = file;
		}

		@Override
		InputStream open() throws IOException {
			return Files.newInputStream(this.file);
		}

	}

}
