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
 * The {@code compile} runtime of a compiled program ({@code compile-runtime.lisp} on the
 * classpath, canonical shape, read with the real {@link LispReader} like
 * {@link MopProtocol}): a definition that defines methods -- postmodern's
 * {@code build-dao-methods} {@code %eval} idiom, whose method construction already ran at
 * definition time and was spliced into the program (see {@link MopEvalCapture}) -- is a
 * deliberate no-op; everything else signals, because a compiled program has no runtime
 * compiler. Injected by {@code LispMacroExpander.expandTopLevelDefinitions}, gated on the
 * program referencing {@code compile} without defining it. The interpreter (and the
 * compile paths' macro-time evaluator) instead carries the real evaluator-backed
 * {@code compile} built-in and never evaluates these forms.
 */
public final class CompileRuntime {

	@Nullable private static volatile List<LispVal> forms;

	private CompileRuntime() {
	}

	/**
	 * The runtime's definition forms, in definition order. Parsed once and cached; the
	 * list is immutable, so callers may splice it into a program directly.
	 * @return the runtime definitions
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (CompileRuntime.class) {
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
		try (InputStream in = CompileRuntime.class.getResourceAsStream("compile-runtime.lisp")) {
			if (in == null) {
				throw new IllegalStateException("compile-runtime.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
