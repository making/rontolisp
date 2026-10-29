package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * Bencode, the encoding nREPL messages travel in: a byte string {@code <length>:<bytes>},
 * an integer {@code i<digits>e}, a list {@code l...e} and a dictionary {@code d...e} of
 * byte-string keys. Read, a byte string is its UTF-8 text (every nREPL slot holds text),
 * an integer a {@link Long}, a list a {@link List} and a dictionary a {@link Map} in the
 * order it came; written, a {@link CharSequence} is a byte string, any integral
 * {@link Number} an integer, an {@link Iterable} a list and a {@link Map} a dictionary
 * with its keys sorted, as the encoding asks.
 */
final class Bencode {

	private Bencode() {
	}

	/**
	 * Reads one value.
	 * @param in the stream
	 * @return the value, or null at the end of the stream before one starts
	 * @throws IOException when the stream fails or ends inside a value, or holds no
	 * bencode
	 */
	static @Nullable Object read(InputStream in) throws IOException {
		int first = in.read();
		return first < 0 ? null : read(in, first);
	}

	private static Object read(InputStream in, int first) throws IOException {
		switch (first) {
			case 'i' -> {
				return number(in, 'e', next(in));
			}
			case 'l' -> {
				List<Object> list = new ArrayList<>();
				for (int c = next(in); c != 'e'; c = next(in)) {
					list.add(read(in, c));
				}
				return list;
			}
			case 'd' -> {
				Map<String, Object> map = new LinkedHashMap<>();
				for (int c = next(in); c != 'e'; c = next(in)) {
					if (!(read(in, c) instanceof String key)) {
						throw new IOException("bencode: a dictionary key is a byte string");
					}
					map.put(key, read(in, next(in)));
				}
				return map;
			}
			default -> {
				if (first < '0' || first > '9') {
					throw new IOException("bencode: no value starts with " + describe(first));
				}
				long length = number(in, ':', first);
				if (length > Integer.MAX_VALUE - 8) {
					throw new IOException("bencode: a byte string of " + length + " bytes");
				}
				return new String(in.readNBytes((int) length), StandardCharsets.UTF_8);
			}
		}
	}

	private static long number(InputStream in, char end, int first) throws IOException {
		boolean negative = first == '-';
		int c = negative ? next(in) : first;
		if (c < '0' || c > '9') {
			throw new IOException("bencode: a number has no digit at " + describe(c));
		}
		long value = 0;
		for (; c != end; c = next(in)) {
			if (c < '0' || c > '9') {
				throw new IOException("bencode: " + describe(c) + " inside a number");
			}
			value = Math.addExact(Math.multiplyExact(value, 10), c - '0');
		}
		return negative ? -value : value;
	}

	private static int next(InputStream in) throws IOException {
		int c = in.read();
		if (c < 0) {
			throw new EOFException("bencode: the stream ended inside a value");
		}
		return c;
	}

	private static String describe(int c) {
		return c >= ' ' && c < 127 ? "'" + (char) c + "'" : "byte " + c;
	}

	/**
	 * Writes one value, as one write of the stream.
	 * @param out the stream
	 * @param value the value
	 * @throws IOException when the stream fails
	 */
	static void write(OutputStream out, Object value) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		encode(bytes, value);
		bytes.writeTo(out);
		out.flush();
	}

	private static void encode(ByteArrayOutputStream out, Object value) {
		switch (value) {
			case CharSequence text -> {
				byte[] octets = text.toString().getBytes(StandardCharsets.UTF_8);
				out.writeBytes((octets.length + ":").getBytes(StandardCharsets.US_ASCII));
				out.writeBytes(octets);
			}
			case Long _, Integer _, Short _, Byte _ ->
				out.writeBytes(("i" + ((Number) value).longValue() + "e").getBytes(StandardCharsets.US_ASCII));
			case Map<?, ?> map -> {
				out.write('d');
				Map<String, Object> sorted = new TreeMap<>();
				map.forEach((key, entry) -> {
					if (entry != null) {
						sorted.put(String.valueOf(key), entry);
					}
				});
				sorted.forEach((key, entry) -> {
					encode(out, key);
					encode(out, entry);
				});
				out.write('e');
			}
			case Iterable<?> list -> {
				out.write('l');
				for (Object item : list) {
					if (item != null) {
						encode(out, item);
					}
				}
				out.write('e');
			}
			default -> throw new IllegalArgumentException("bencode has no encoding of " + value.getClass().getName());
		}
	}

}
