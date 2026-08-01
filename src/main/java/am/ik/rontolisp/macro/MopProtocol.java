package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The metaclass-protocol runtime: the system default methods of the MOP class-definition
 * generics ({@code validate-superclass}, {@code direct-slot-definition-class},
 * {@code effective-slot-definition-class}, {@code compute-effective-slot-definition},
 * {@code finalize-inheritance}) and the {@code %ensure-class-with-metaclass} driver that
 * a {@code (defclass ... (:metaclass
 * M))} expansion calls at definition time.
 *
 * <p>
 * Like {@link FormatRenderer}, the implementation is Lisp source
 * ({@code mop-protocol.lisp} on the classpath, canonical shape) read with the real
 * {@link LispReader}, so every consumer gets the identical forms:
 * <ul>
 * <li>the compile paths prepend {@link #forms()} to the program from
 * {@code LispMacroExpander.expandTopLevelDefinitions}, gated on a {@code :metaclass}
 * defclass -- the defmethods walk through the ordinary dispatcher placement;</li>
 * <li>the interpreter (and thus the compile paths' macro-time evaluator) evaluates the
 * same forms once when it first evaluates a {@code :metaclass} defclass.</li>
 * </ul>
 *
 * The file carries defMETHODs only, no defgenerics: a user protocol method (postmodern
 * defines its hooks BEFORE its first {@code :metaclass} class on the interpreter, and the
 * compile paths inject these defaults ahead of the walk) auto-creates the generic, and
 * the two registration orders must both merge into one generic.
 */
public final class MopProtocol {

	@Nullable private static volatile List<LispVal> forms;

	private MopProtocol() {
	}

	/**
	 * The protocol's definition forms, in definition order. Parsed once and cached; the
	 * list is immutable, so callers may splice it into a program directly.
	 * @return the protocol definitions
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (MopProtocol.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource()));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = MopProtocol.class.getResourceAsStream("mop-protocol.lisp")) {
			if (in == null) {
				throw new IllegalStateException("mop-protocol.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
