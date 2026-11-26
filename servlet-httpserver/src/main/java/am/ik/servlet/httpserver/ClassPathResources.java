package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.Servlet;
import org.jspecify.annotations.Nullable;

/**
 * The resources under {@code META-INF/resources} of the class path (Servlet 6.1, 4.6: a
 * library's resources), as the application's class loader finds them.
 *
 * <p>
 * Under a JDK each is asked what it is: a file of a directory or an entry of an archive
 * ({@link Located}). A program compiled ahead of time has the resources it was compiled
 * with and no file they came from: a resource there is its octets, a directory reads as
 * the names of its entries, and a resource is taken as modified when the server was made,
 * after which it cannot change.
 */
final class ClassPathResources {

	private final ClassLoader loader;

	/** When a resource of no time of its own was modified last, in whole seconds. */
	private final long made;

	ClassPathResources(ClassLoader loader) {
		this.loader = loader;
		this.made = System.currentTimeMillis() / 1000 * 1000;
	}

	/** The default servlet, made where the class path holds resources. */
	Servlet servlet(WebContext context) {
		return new DefaultServlet(context);
	}

	/**
	 * The file or the directory of a path.
	 * @param path a normalized path starting with a slash
	 * @return what is there, or null for nothing
	 */
	@Nullable Resource find(String path) throws IOException {
		if (System.getProperty("java.class.path") != null) {
			URL url = this.loader.getResource("META-INF/resources" + path);
			return url == null ? null : Located.of(url);
		}
		InputStream in = this.loader.getResourceAsStream("META-INF/resources" + path);
		if (in == null) {
			return null;
		}
		byte[] octets;
		try (in) {
			octets = in.readAllBytes();
		}
		return new Resource.Octets(octets, below(path, octets), this.made);
	}

	/**
	 * Whether the octets of a resource compiled ahead of time are a directory's: its
	 * entries' names, a line each, of which the first is a resource below it. A file has
	 * none below it; an empty directory, and a directory of an archive, read as nothing
	 * and are taken for empty files.
	 */
	private boolean below(String path, byte[] octets) throws IOException {
		int end = 0;
		while (end < octets.length && octets[end] != '\n') {
			end++;
		}
		if (end == 0 || end == octets.length) {
			return false;
		}
		String first = new String(octets, 0, end, StandardCharsets.UTF_8);
		if (first.indexOf('/') >= 0) {
			return false;
		}
		InputStream entry = this.loader
			.getResourceAsStream("META-INF/resources" + path + (path.endsWith("/") ? "" : "/") + first);
		if (entry == null) {
			return false;
		}
		entry.close();
		return true;
	}

	/** The URL of the file or the directory of a path, or null. */
	URL url(String path) {
		return this.loader.getResource("META-INF/resources" + path);
	}

	/** The octets of the file of a path, or null where it is no file. */
	@Nullable InputStream stream(String path) throws IOException {
		Resource found = find(path);
		return found == null || found.directory ? null : found.open();
	}

	/**
	 * The names of the entries of a directory in every place of the class path that holds
	 * it, a directory's with a slash.
	 * @param directory a normalized path ending with a slash
	 */
	List<String> names(String directory) throws IOException {
		List<String> names = new ArrayList<>();
		Enumeration<URL> places = this.loader.getResources("META-INF/resources" + directory);
		while (places.hasMoreElements()) {
			URL place = places.nextElement();
			if (System.getProperty("java.class.path") != null) {
				Located.names(place, names);
				continue;
			}
			byte[] listing;
			try (InputStream in = place.openStream()) {
				listing = in.readAllBytes();
			}
			int start = 0;
			for (int i = 0; i < listing.length; i++) {
				if (listing[i] == '\n') {
					String name = new String(listing, start, i - start, StandardCharsets.UTF_8);
					Resource entry = find(directory + name);
					names.add(entry != null && entry.directory ? name + "/" : name);
					start = i + 1;
				}
			}
		}
		return names;
	}

}
