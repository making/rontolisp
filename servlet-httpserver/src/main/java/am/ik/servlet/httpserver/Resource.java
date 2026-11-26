package am.ik.servlet.httpserver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A resource of the application a path finds (Servlet 6.1, 4.6): a file, or a directory,
 * which has nothing to read.
 */
abstract sealed class Resource permits Resource.Octets, Located, DocumentRoot.Found {

	final boolean directory;

	/** Its length in octets. */
	final long length;

	/** When it was modified last, in milliseconds, or 0 where that is not known. */
	final long modified;

	Resource(boolean directory, long length, long modified) {
		this.directory = directory;
		this.length = length;
		this.modified = modified;
	}

	/** Its octets from the first. */
	abstract InputStream open() throws IOException;

	/** Writes a run of its octets. */
	void write(OutputStream out, long from, long count) throws IOException {
		try (InputStream in = open()) {
			long skipped = 0;
			while (skipped < from) {
				long by = in.skip(from - skipped);
				if (by <= 0) {
					if (in.read() < 0) {
						return;
					}
					by = 1;
				}
				skipped += by;
			}
			byte[] buffer = new byte[8192];
			long left = count;
			while (left > 0) {
				int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
				if (read < 0) {
					return;
				}
				out.write(buffer, 0, read);
				left -= read;
			}
		}
	}

	/**
	 * The path of a resource with its {@code .} and {@code ..} resolved and its empty
	 * segments taken away, a directory's with its last slash.
	 * @param path a path starting with a slash
	 * @return the path, or null where it climbs above the top or holds a backslash or a
	 * control character
	 */
	static @Nullable String normalized(String path) {
		List<String> segments = new ArrayList<>();
		boolean directory = false;
		int start = 1;
		while (start <= path.length()) {
			int end = path.indexOf('/', start);
			if (end < 0) {
				end = path.length();
			}
			String segment = path.substring(start, end);
			if (segment.equals("..")) {
				if (segments.isEmpty()) {
					return null;
				}
				segments.removeLast();
			}
			else if (!segment.isEmpty() && !segment.equals(".")) {
				for (int i = 0; i < segment.length(); i++) {
					char c = segment.charAt(i);
					if (c < 0x20 || c == 0x7F || c == '\\') {
						return null;
					}
				}
				segments.add(segment);
			}
			directory = segment.isEmpty() || segment.equals(".") || segment.equals("..");
			start = end + 1;
		}
		StringBuilder normal = new StringBuilder();
		for (int i = 0; i < segments.size(); i++) {
			normal.append('/').append(segments.get(i));
		}
		return segments.isEmpty() || directory ? normal.append('/').toString() : normal.toString();
	}

	/** A resource read whole: its octets in memory. */
	static final class Octets extends Resource {

		private final byte[] octets;

		Octets(byte[] octets, boolean directory, long modified) {
			super(directory, octets.length, modified);
			this.octets = octets;
		}

		@Override
		InputStream open() {
			return new ByteArrayInputStream(this.octets);
		}

		@Override
		void write(OutputStream out, long from, long count) throws IOException {
			out.write(this.octets, (int) from, (int) count);
		}

	}

}
