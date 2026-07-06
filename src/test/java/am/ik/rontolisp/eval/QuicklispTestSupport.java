package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Test helpers for the {@link QuicklispClient}: builds an in-memory Quicklisp
 * distribution (distinfo + {@code systems.txt} + {@code releases.txt} + release tarballs)
 * and a {@link QuicklispClient.Downloader} serving it, so quickload can be exercised with
 * no network access. The tarball writer emits a minimal USTAR archive (name, size,
 * regular-file typeflag) which is all {@link QuicklispClient}'s extractor reads.
 */
public final class QuicklispTestSupport {

	/** The distinfo URL the client bootstraps from (matches the production constant). */
	public static final String DISTINFO_URL = "https://beta.quicklisp.org/dist/quicklisp.txt";

	public static final String SYSTEMS_URL = "http://fake.quicklisp/systems.txt";

	public static final String RELEASES_URL = "http://fake.quicklisp/releases.txt";

	private QuicklispTestSupport() {
	}

	/**
	 * A recording downloader that serves a fixed URL-to-bytes map and counts how many
	 * times each URL was fetched (so a test can assert the cache prevents re-downloads).
	 */
	public static final class RecordingDownloader implements QuicklispClient.Downloader {

		private final Map<String, byte[]> responses;

		public final Map<String, Integer> hits = new HashMap<>();

		public RecordingDownloader(Map<String, byte[]> responses) {
			this.responses = responses;
		}

		@Override
		public byte[] get(String url) throws IOException {
			this.hits.merge(url, 1, Integer::sum);
			byte[] body = this.responses.get(url);
			if (body == null) {
				throw new IOException("no fake response for " + url);
			}
			return body;
		}

	}

	/**
	 * Builds a downloader for a distribution with the given {@code systems.txt} and
	 * {@code releases.txt} bodies and the given release tarballs (keyed by URL).
	 * @param systemsTxt the {@code systems.txt} body
	 * @param releasesTxt the {@code releases.txt} body
	 * @param tarballs release tarball bytes keyed by their download URL
	 * @return a recording downloader serving the whole distribution
	 */
	public static RecordingDownloader dist(String systemsTxt, String releasesTxt, Map<String, byte[]> tarballs) {
		Map<String, byte[]> responses = new HashMap<>();
		String distinfo = "name: quicklisp\nversion: test\nsystem-index-url: " + SYSTEMS_URL + "\nrelease-index-url: "
				+ RELEASES_URL + "\n";
		responses.put(DISTINFO_URL, distinfo.getBytes(StandardCharsets.UTF_8));
		responses.put(SYSTEMS_URL, systemsTxt.getBytes(StandardCharsets.UTF_8));
		responses.put(RELEASES_URL, releasesTxt.getBytes(StandardCharsets.UTF_8));
		responses.putAll(tarballs);
		return new RecordingDownloader(responses);
	}

	/**
	 * Builds a gzip-compressed USTAR tar archive from {@code files} (archive path to
	 * content), the shape {@link QuicklispClient} extracts.
	 * @param files the archive entries: path to UTF-8 content
	 * @return the {@code .tar.gz} bytes
	 */
	public static byte[] tarGz(Map<String, String> files) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			for (Map.Entry<String, String> entry : files.entrySet()) {
				byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
				gz.write(ustarHeader(entry.getKey(), content.length));
				gz.write(content);
				int pad = (512 - (content.length % 512)) % 512;
				if (pad > 0) {
					gz.write(new byte[pad]);
				}
			}
			// Two trailing zero blocks mark the end of the archive.
			gz.write(new byte[1024]);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return bos.toByteArray();
	}

	private static byte[] ustarHeader(String name, int size) {
		byte[] header = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
		writeOctal(header, 124, 12, size);
		header[156] = '0'; // regular file
		byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(magic, 0, header, 257, magic.length);
		return header;
	}

	private static void writeOctal(byte[] header, int offset, int length, long value) {
		String digits = Long.toOctalString(value);
		StringBuilder field = new StringBuilder();
		for (int i = 0; i < length - 1 - digits.length(); i++) {
			field.append('0');
		}
		field.append(digits);
		byte[] bytes = field.toString().getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(bytes, 0, header, offset, Math.min(length - 1, bytes.length));
		// The trailing byte stays NUL (a valid octal-field terminator).
	}

}
