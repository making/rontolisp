package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A resource of the class path as a JDK finds it, asked what it is: a file or a directory
 * of a directory, an entry of an archive, or what a URL of another kind connects to.
 * Reached only where there is a class path.
 */
final class Located extends Resource {

	private final URL url;

	private Located(URL url, boolean directory, long length, long modified) {
		super(directory, length, modified);
		this.url = url;
	}

	static Resource of(URL url) throws IOException {
		if (url.getProtocol().equals("file")) {
			Path file = path(url);
			return Files.isDirectory(file) ? new Located(url, true, 0, 0)
					: new Located(url, false, Files.size(file), Files.getLastModifiedTime(file).toMillis());
		}
		URLConnection connection = url.openConnection();
		if (connection instanceof JarURLConnection archive) {
			// the entry's own time, where the connection would tell the archive's
			JarEntry entry = archive.getJarEntry();
			if (entry.isDirectory()) {
				return new Located(url, true, 0, 0);
			}
			if (entry.getSize() >= 0) {
				return new Located(url, false, entry.getSize(), Math.max(entry.getTime(), 0));
			}
		}
		else if (connection.getContentLengthLong() >= 0) {
			return new Located(url, false, connection.getContentLengthLong(),
					Math.max(connection.getLastModified(), 0));
		}
		// a length no one tells: what is read
		try (InputStream in = connection.getInputStream()) {
			return new Resource.Octets(in.readAllBytes(), false, Math.max(connection.getLastModified(), 0));
		}
	}

	@Override
	InputStream open() throws IOException {
		return this.url.openStream();
	}

	/**
	 * Adds the names of a directory's entries, a directory's with a slash: of a directory
	 * the files it holds, of an archive the entries below it.
	 */
	static void names(URL directory, List<String> names) throws IOException {
		if (directory.getProtocol().equals("file")) {
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(path(directory))) {
				for (Path entry : entries) {
					names.add(entry.getFileName() + (Files.isDirectory(entry) ? "/" : ""));
				}
			}
			return;
		}
		if (!(directory.openConnection() instanceof JarURLConnection archive)) {
			return;
		}
		String prefix = archive.getEntryName();
		if (!prefix.endsWith("/")) {
			prefix += "/";
		}
		Set<String> below = new LinkedHashSet<>();
		JarFile jar = archive.getJarFile();
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			String name = entries.nextElement().getName();
			if (name.length() > prefix.length() && name.startsWith(prefix)) {
				int slash = name.indexOf('/', prefix.length());
				// an entry below a directory of its own names the directory
				below.add(slash < 0 ? name.substring(prefix.length()) : name.substring(prefix.length(), slash + 1));
			}
		}
		names.addAll(below);
	}

	private static Path path(URL url) throws IOException {
		try {
			return Path.of(url.toURI());
		}
		catch (URISyntaxException ex) {
			throw new IOException("not a path: " + url, ex);
		}
	}

}
