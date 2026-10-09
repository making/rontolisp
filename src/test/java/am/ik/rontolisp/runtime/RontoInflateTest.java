package am.ik.rontolisp.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import am.ik.rontolisp.testsupport.InflateCases;
import am.ik.rontolisp.testsupport.InflateCases.Case;
import am.ik.rontolisp.testsupport.InflateCases.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the interpreter's and the JVM's decoder to {@code java.util.zip}: every case of
 * {@link InflateCases} fed a chunk of 1, 3, 64 octets or all at once reads as
 * {@code GZIPInputStream} / {@code InflaterInputStream} read it -- the same octets, or
 * the same exception class and message.
 */
class RontoInflateTest {

	private static final List<Case> CASES = InflateCases.generate(42, 60);

	@Test
	void everyCaseReadsAsJavaUtilZipReadsItWhereverItsChunksAreCut() {
		for (Case c : CASES) {
			Outcome expected = InflateCases.oracle(c);
			for (int chunk : new int[] { 1, 3, 64, Integer.MAX_VALUE }) {
				assertThat(decode(c, chunk, -1)).as("%s in chunks of %d", c, chunk).isEqualTo(expected);
			}
		}
	}

	@Test
	void aLimitedUpdateAnswersAtMostTheLimitAndTheRestFollows() {
		for (Case c : CASES) {
			Outcome expected = InflateCases.oracle(c);
			assertThat(decode(c, 7, 1)).as("%s a octet at a time", c).isEqualTo(expected);
			assertThat(decode(c, Integer.MAX_VALUE, 100)).as("%s 100 octets at a time", c).isEqualTo(expected);
		}
	}

	@Test
	void aZeroLimitReadsTheHeaderAndNothingMore() {
		byte[] gzip = InflateCases.gzip("hello".getBytes(StandardCharsets.UTF_8));
		RontoInflate decoder = new RontoInflate(RontoInflate.GZIP);
		assertThat((byte[]) decoder.update(gzip, 0, 5, 0)).isEmpty();
		assertThat(decoder.finish()).isEqualTo(RontoInflate.SHORT_HEADER);
		assertThat((byte[]) decoder.update(gzip, 5, 5, 0)).isEmpty();
		assertThat(decoder.finish()).isEqualTo(RontoInflate.SHORT_DATA);
		assertThat(new String((byte[]) decoder.update(gzip, 10, gzip.length - 10, -1), StandardCharsets.UTF_8))
			.isEqualTo("hello");
		assertThat(decoder.finish()).isZero();
		RontoInflate bad = new RontoInflate(RontoInflate.GZIP);
		assertThat(bad.update(new byte[] { 0x1f, 0x00 }, 0, 2, 0)).isEqualTo("Not in GZIP format");
		assertThat(bad.update(gzip, 0, gzip.length, -1)).as("a malformed stream stays malformed")
			.isEqualTo("Not in GZIP format");
	}

	// nil is null in the compiled representation the entry points speak
	@SuppressWarnings("NullAway")
	@Test
	void theJvmEntryPointsSpeakTheCompiledValueRepresentation() {
		byte[] gzip = InflateCases.gzip("héllo".getBytes(StandardCharsets.UTF_8));
		byte[] packed = new byte[gzip.length + 1];
		packed[0] = 8;
		System.arraycopy(gzip, 0, packed, 1, gzip.length);
		Object decoder = RontoInflate.create(2L);
		Object out = RontoInflate.update(decoder, packed, null);
		assertThat(out).isInstanceOf(byte[].class);
		byte[] tagged = (byte[]) out;
		assertThat(tagged[0]).isEqualTo((byte) 8);
		assertThat(new String(tagged, 1, tagged.length - 1, StandardCharsets.UTF_8)).isEqualTo("héllo");
		assertThat(RontoInflate.finish(decoder)).isNull();
		Object raw = RontoInflate.create(0L);
		assertThat(RontoInflate.update(raw, new byte[] { 8, 7 }, null)).isEqualTo("\"invalid block type\"");
		Object cut = RontoInflate.create(2L);
		assertThat(RontoInflate.update(cut, new byte[] { 8, 0x1f }, 1L)).isEqualTo(new byte[] { 8 });
		assertThat(RontoInflate.finish(cut)).isEqualTo(1L);
	}

	@Test
	void aLongTextDecodesWhole() {
		byte[] plain = InflateCases.text(new Random(7), 200_000);
		Outcome expected = Outcome.ok(plain);
		for (int kind : new int[] { InflateCases.GZIP, InflateCases.ZLIB, InflateCases.RAW }) {
			byte[] input = switch (kind) {
				case InflateCases.GZIP -> InflateCases.gzip(plain);
				case InflateCases.ZLIB -> InflateCases.deflate(plain, 9, false, 0);
				default -> InflateCases.deflate(plain, 1, true, 0);
			};
			assertThat(decode(new Case(kind, input), 65536, -1)).isEqualTo(expected);
			assertThat(decode(new Case(kind, input), 1000, -1)).isEqualTo(expected);
		}
	}

	/**
	 * The case fed to a decoder {@code chunk} octets at a time, each update answering at
	 * most {@code limit} octets and the decoder drained before the next chunk, as the
	 * Clojure client reads a reply.
	 */
	static Outcome decode(Case c, int chunk, int limit) {
		RontoInflate decoder = new RontoInflate(c.kind());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] input = c.input();
		byte[] none = new byte[0];
		int i = 0;
		do {
			int n = Math.min(chunk, input.length - i);
			Object answer = decoder.update(input, i, n, limit);
			i += n;
			while (true) {
				if (answer instanceof String message) {
					return Outcome.zip(message);
				}
				byte[] octets = (byte[]) answer;
				assertThat(limit < 0 || octets.length <= limit).isTrue();
				out.writeBytes(octets);
				if (octets.length == 0 || limit < 0) {
					break;
				}
				answer = decoder.update(none, 0, 0, limit);
			}
		}
		while (i < input.length);
		return switch (decoder.finish()) {
			case 0 -> Outcome.ok(out.toByteArray());
			case RontoInflate.SHORT_HEADER -> Outcome.eof(null);
			default -> Outcome.eof(InflateCases.UNEXPECTED_END);
		};
	}

}
