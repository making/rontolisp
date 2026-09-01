package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads the YAML test resources ({@code ci-spec.yaml}, {@code examples.yaml}) through a
 * Reader that removes a snakeyaml-engine buffer-boundary landmine every reader of these
 * files would otherwise share.
 */
public final class YamlResources {

	/**
	 * One {@code ci-spec.yaml} case as the shaker/corpus tests need it: name, source, and
	 * the expected output (single string or per-backend map).
	 */
	public record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {
	}

	/** The {@code ci-spec.yaml} shape the shaker/corpus tests parse. */
	public record Spec(List<Case> cases) {
	}

	private YamlResources() {
	}

	/**
	 * Parses {@code /ci-spec.yaml} into {@link Spec}.
	 * @return the parsed corpus
	 * @throws IOException if the resource is missing or unreadable
	 */
	public static Spec readCiSpec() throws IOException {
		try (InputStream in = YamlResources.class.getResourceAsStream("/ci-spec.yaml")) {
			if (in == null) {
				throw new IOException("missing test resource: /ci-spec.yaml");
			}
			return new YAMLMapper().readValue(safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
					Spec.class);
		}
	}

	/**
	 * The whole {@code ci-spec.yaml} corpus as one program: every case source
	 * concatenated in order, each newline-terminated.
	 * @return the concatenated corpus source
	 * @throws IOException if the resource is missing or unreadable
	 */
	public static String corpusSource() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Case c : readCiSpec().cases()) {
			sb.append(c.source());
			if (!c.source().endsWith("\n")) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * Wraps {@code text} in a Reader that never completes a read exactly on a high
	 * surrogate, so snakeyaml-engine 3.0.1 cannot hit its StreamReader crash.
	 *
	 * <p>
	 * 3.0.1 {@code StreamReader.update()} reads a FULL internal buffer and then, when the
	 * last char read is a high surrogate, asks the underlying Reader for one more char at
	 * {@code offset == buffer.length} -- an out-of-bounds request against the very buffer
	 * that just filled, so a high surrogate landing at a buffer multiple throws
	 * {@link IndexOutOfBoundsException} mid-parse (upstream fixed {@code update()} to
	 * read {@code buffer.length - 1}; no release carries that fix while this build pins
	 * 3.0.1). Handing the mapper the raw byte stream fails the same family of way -- its
	 * reader splits a multi-byte UTF-8 character across a buffer boundary -- which is why
	 * these resources used to decode to a String first and pass the String. Pre-decoding
	 * does not help: the surrogate simply moves from a byte boundary to a CHARACTER
	 * boundary. Which character lands on the boundary depends on every byte before it, so
	 * an unrelated edit to ci-spec.yaml arms or disarms the crash -- this wrapper removes
	 * the landmine instead.
	 *
	 * <p>
	 * The wrapper returns one char short of a full request when the char that would end
	 * it is a high surrogate, rewinding the underlying StringReader (mark/reset) so the
	 * next read starts with that surrogate instead of losing it. Short reads are ordinary
	 * {@link Reader} behavior and snakeyaml handles them.
	 * @param text the document text
	 * @return a Reader over the same characters
	 */
	public static Reader safeReader(String text) {
		StringReader sr = new StringReader(text);
		return new Reader() {
			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				if (len < 2) {
					// A single-char read returns the surrogate as-is -- that is normal
					// Reader behavior and cannot complete a snakeyaml buffer.
					return sr.read(cbuf, off, len);
				}
				sr.mark(len);
				int n = sr.read(cbuf, off, len);
				if (n == len && Character.isHighSurrogate(cbuf[off + n - 1])) {
					sr.reset();
					// Re-read without the trailing surrogate; the char before it cannot
					// be a high surrogate in well-formed text, so this window ends
					// cleanly.
					return sr.read(cbuf, off, len - 1);
				}
				return n;
			}

			@Override
			public void close() throws IOException {
				sr.close();
			}
		};
	}

}
