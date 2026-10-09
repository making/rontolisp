package am.ik.rontolisp.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import org.jspecify.annotations.Nullable;

/**
 * Compressed streams -- whole, cut short, corrupted, with every gzip header field and
 * member shape -- and what {@code java.util.zip} reads each as: the oracle the decoders
 * of {@code rontolisp::%inflate-update} are held to (babashka.http-client decompresses a
 * reply through {@code GZIPInputStream} and {@code InflaterInputStream}).
 */
public final class InflateCases {

	/** A raw DEFLATE stream ({@code RontoInflate.RAW}). */
	public static final int RAW = 0;

	/** A zlib stream ({@code RontoInflate.ZLIB}). */
	public static final int ZLIB = 1;

	/** A gzip stream ({@code RontoInflate.GZIP}). */
	public static final int GZIP = 2;

	/** The message {@code InflaterInputStream} reports compressed data cut short with. */
	public static final String UNEXPECTED_END = "Unexpected end of ZLIB input stream";

	private InflateCases() {
	}

	/**
	 * One compressed input of a kind.
	 *
	 * @param kind the kind
	 * @param input the compressed octets
	 */
	public record Case(int kind, byte[] input) {

		@Override
		public String toString() {
			return "kind " + this.kind + ", " + this.input.length + " octets: "
					+ Arrays.toString(this.input.length > 48 ? Arrays.copyOf(this.input, 48) : this.input);
		}

	}

	/**
	 * What a stream reads as: its octets, or the class and message of what was thrown.
	 *
	 * @param octets the octets read, or null when the read threw
	 * @param thrown {@code "zip"} for a {@code ZipException}, {@code "eof"} for an
	 * {@code EOFException}, or null
	 * @param message the exception's message
	 */
	public record Outcome(byte @Nullable [] octets, @Nullable String thrown, @Nullable String message) {

		public static Outcome ok(byte[] octets) {
			return new Outcome(octets, null, null);
		}

		public static Outcome zip(@Nullable String message) {
			return new Outcome(null, "zip", message);
		}

		public static Outcome eof(@Nullable String message) {
			return new Outcome(null, "eof", message);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Outcome o && Arrays.equals(this.octets, o.octets)
					&& java.util.Objects.equals(this.thrown, o.thrown)
					&& java.util.Objects.equals(this.message, o.message);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.octets);
		}

		@Override
		public String toString() {
			return (this.octets != null) ? "ok " + this.octets.length + " octets" : this.thrown + " " + this.message;
		}

	}

	/**
	 * What {@code java.util.zip} reads the case as: {@code GZIPInputStream} for gzip,
	 * {@code InflaterInputStream} over a zlib or a raw (nowrap) {@code Inflater}.
	 * @param c the case
	 * @return the outcome
	 */
	public static Outcome oracle(Case c) {
		try {
			try (InputStream in = open(c)) {
				return Outcome.ok(in.readAllBytes());
			}
		}
		catch (ZipException ex) {
			return Outcome.zip(ex.getMessage());
		}
		catch (EOFException ex) {
			return Outcome.eof(ex.getMessage());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static InputStream open(Case c) throws IOException {
		ByteArrayInputStream bytes = new ByteArrayInputStream(c.input());
		return switch (c.kind()) {
			case GZIP -> new GZIPInputStream(bytes);
			case ZLIB -> new InflaterInputStream(bytes);
			default -> new InflaterInputStream(bytes, new Inflater(true));
		};
	}

	/**
	 * The cases: whole streams of every kind at several levels and strategies over text,
	 * random octets, a long run and nothing; gzip members back to back and followed by
	 * what is no member; every gzip header field; each of the three cut short at a spread
	 * of lengths and corrupted at a spread of octets; random octets behind a valid
	 * header; the zlib header's refusals and its preset dictionary; stored blocks well
	 * and badly formed.
	 * @param seed the random seed
	 * @param scale how many random cases of each random family (the corruptions included)
	 * @return the cases
	 */
	public static List<Case> generate(long seed, int scale) {
		Random random = new Random(seed);
		List<Case> cases = new ArrayList<>();
		List<byte[]> plains = List.of(new byte[0], "a".getBytes(StandardCharsets.UTF_8), text(random, 50),
				text(random, 400), randomOctets(random, 300), run(1000), text(random, 3000));
		int[] levels = { 0, 1, 6, 9 };
		int[] strategies = { Deflater.DEFAULT_STRATEGY, Deflater.HUFFMAN_ONLY, Deflater.FILTERED };
		for (byte[] plain : plains) {
			cases.add(new Case(GZIP, gzip(plain)));
			for (int level : levels) {
				for (int strategy : strategies) {
					cases.add(new Case(ZLIB, deflate(plain, level, false, strategy)));
					cases.add(new Case(RAW, deflate(plain, level, true, strategy)));
				}
			}
		}
		cases.add(new Case(GZIP, concat(gzip(text(random, 20)), gzip(text(random, 30)))));
		cases.add(new Case(GZIP, concat(gzip(text(random, 20)), new byte[] { 1, 2, 3 })));
		cases.add(new Case(GZIP, concat(gzip(text(random, 20)), new byte[] { 0x1f })));
		cases.add(new Case(GZIP, concat(gzip(text(random, 20)), Arrays.copyOf(gzip(text(random, 5)), 12))));
		cases.add(new Case(GZIP, concat(gzip(text(random, 20)), gzip(new byte[0]), gzip(text(random, 9)))));
		cases.addAll(headerFields(text(random, 10)));
		for (int kind : new int[] { GZIP, ZLIB, RAW }) {
			byte[] base = switch (kind) {
				case GZIP -> gzip(text(random, 60));
				case ZLIB -> deflate(text(random, 60), 6, false, Deflater.DEFAULT_STRATEGY);
				default -> deflate(text(random, 60), 6, true, Deflater.DEFAULT_STRATEGY);
			};
			for (int cut = 0; cut < base.length; cut += (cut < 24 || cut > base.length - 12) ? 1 : 5) {
				cases.add(new Case(kind, Arrays.copyOf(base, cut)));
			}
			for (int i = 0; i < scale; i++) {
				byte[] corrupt = base.clone();
				int at = random.nextInt(corrupt.length);
				corrupt[at] ^= (byte) (1 + random.nextInt(255));
				cases.add(new Case(kind, corrupt));
			}
		}
		for (int i = 0; i < scale; i++) {
			cases.add(new Case(RAW, randomOctets(random, 1 + random.nextInt(40))));
			cases.add(new Case(ZLIB,
					concat(new byte[] { 0x78, (byte) 0x9c }, randomOctets(random, 1 + random.nextInt(40)))));
			cases.add(new Case(GZIP, concat(new byte[] { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0 },
					randomOctets(random, 1 + random.nextInt(40)))));
		}
		cases.add(new Case(ZLIB, octets(0x78, 0x9d, 1, 2, 3)));
		cases.add(new Case(ZLIB, octets(0x79, 0x9c, 1, 2, 3)));
		cases.add(new Case(ZLIB, octets(0x88, 0x98, 1, 2, 3)));
		cases.add(new Case(ZLIB, octets(0x78, 0xbb, 0, 0, 0, 1, 3, 0)));
		cases.add(new Case(ZLIB, octets(0x78, 0xbb, 0, 0, 0)));
		cases.add(new Case(RAW, octets(1, 3, 0, 0xfc, 0xff, 65, 66, 67)));
		cases.add(new Case(RAW, octets(1, 3, 0, 0xfd, 0xff, 65, 66, 67)));
		cases.add(new Case(RAW, octets(0, 1, 0, 0xfe, 0xff, 65, 1, 1, 0, 0xfe, 0xff, 66)));
		cases.add(new Case(RAW, octets(7)));
		return cases;
	}

	/** Every gzip header field, each one alone and all together, and a bad method. */
	private static List<Case> headerFields(byte[] plain) {
		byte[] body = deflate(plain, 6, true, Deflater.DEFAULT_STRATEGY);
		byte[] trailer = trailer(plain);
		List<Case> cases = new ArrayList<>();
		cases.add(new Case(GZIP, concat(header(4, 3, 0, 7, 8, 9), body, trailer)));
		cases.add(new Case(GZIP, concat(header(8, 65, 66, 0), body, trailer)));
		cases.add(new Case(GZIP, concat(header(16, 67, 0), body, trailer)));
		byte[] crcOnly = header(2);
		cases.add(new Case(GZIP, concat(crcOnly, headerCrc(crcOnly), body, trailer)));
		cases.add(new Case(GZIP, concat(crcOnly, octets(0, 0), body, trailer)));
		byte[] all = header(30, 1, 0, 9, 65, 0, 66, 0);
		cases.add(new Case(GZIP, concat(all, headerCrc(all), body, trailer)));
		cases.add(new Case(GZIP, concat(octets(0x1f, 0x8b, 7, 0, 0, 0, 0, 0, 0, 3), body, trailer)));
		return cases;
	}

	private static byte[] header(int flags, int... extra) {
		return concat(octets(0x1f, 0x8b, 8, flags, 0, 0, 0, 0, 0, 3), octets(extra));
	}

	private static byte[] headerCrc(byte[] header) {
		CRC32 crc = new CRC32();
		crc.update(header);
		long v = crc.getValue();
		return octets((int) (v & 0xff), (int) ((v >> 8) & 0xff));
	}

	private static byte[] trailer(byte[] plain) {
		CRC32 crc = new CRC32();
		crc.update(plain);
		long v = crc.getValue();
		int n = plain.length;
		return octets((int) (v & 0xff), (int) ((v >> 8) & 0xff), (int) ((v >> 16) & 0xff), (int) ((v >> 24) & 0xff),
				n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff);
	}

	/**
	 * The gzip stream of the octets, as {@code GZIPOutputStream} writes it.
	 * @param plain the octets
	 * @return the stream
	 */
	public static byte[] gzip(byte[] plain) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream g = new GZIPOutputStream(out)) {
			g.write(plain);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	/**
	 * The zlib (or, with {@code nowrap}, raw DEFLATE) stream of the octets.
	 * @param plain the octets
	 * @param level the compression level
	 * @param nowrap whether the stream has no zlib header and check
	 * @param strategy the {@code Deflater} strategy
	 * @return the stream
	 */
	public static byte[] deflate(byte[] plain, int level, boolean nowrap, int strategy) {
		Deflater deflater = new Deflater(level, nowrap);
		deflater.setStrategy(strategy);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (DeflaterOutputStream d = new DeflaterOutputStream(out, deflater)) {
			d.write(plain);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			deflater.end();
		}
		return out.toByteArray();
	}

	/**
	 * Text of words, some of them outside ASCII.
	 * @param random the source
	 * @param words how many words
	 * @return its UTF-8 octets
	 */
	public static byte[] text(Random random, int words) {
		String[] pool = { "alpha ", "beta ", "gamma ", "delta\n", "héllo ", "世界 ", "x", "yy" };
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < words; i++) {
			sb.append(pool[random.nextInt(pool.length)]);
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] randomOctets(Random random, int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	private static byte[] run(int n) {
		byte[] b = new byte[n];
		Arrays.fill(b, (byte) 'A');
		return b;
	}

	private static byte[] octets(int... values) {
		byte[] b = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			b[i] = (byte) values[i];
		}
		return b;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

}
