package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * rontolisp's own Gray-stream extension ({@code gray.lisp}): the base classes and generic
 * functions a user-defined character output stream implements, mirroring how real
 * implementations expose their native Gray support. The
 * {@code write-char}/{@code write-string} built-ins dispatch to
 * {@code rontolisp:stream-write-string} when handed a CLOS instance instead of a stream
 * handle; the {@code trivial-gray-streams} shim system adapts the portable protocol onto
 * this one, so no third-party name is known to the core.
 */
public final class GrayStreamsLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private GrayStreamsLibrary() {
	}

	/**
	 * Returns the parsed protocol definitions (canonical shape, no package resolution
	 * needed). Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (GrayStreamsLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = GrayStreamsLibrary.class.getResourceAsStream("gray.lisp")) {
			if (in == null) {
				throw new IllegalStateException("gray.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
