package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL Scheme front end ({@code scheme.lisp} on the
 * classpath): the printer behind {@code display}/{@code write}, an {@code equal?} that
 * recurses into vectors, the symbol-name escaping, {@code call/cc} and
 * {@code dynamic-wind}. It is Common Lisp source like every other shipped library, so no
 * backend learns a Scheme name ({@code .kb/scheme-frontend.md}).
 *
 * <p>
 * Consumers, the {@link UrlLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%scheme-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Scheme program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 */
public final class SchemeLibrary {

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile Set<String> functionNames;

	private SchemeLibrary() {
	}

	/**
	 * Returns the parsed library definitions. The source is written in canonical shape
	 * (internal double-colon {@code rontolisp::} helpers, bare {@code cl} names), so it
	 * needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (SchemeLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = SchemeLibrary.class.getResourceAsStream("scheme.lisp")) {
			if (in == null) {
				throw new IllegalStateException("scheme.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code scheme.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isSchemeFunction(String symbolName) {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			functionNames = names;
		}
		return names.contains(symbolName);
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions. A program that does not is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				List<LispVal> out = new ArrayList<>(forms());
				out.addAll(program);
				return out;
			}
		}
		return program;
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isSchemeFunction(symbol.name());
	}

}
