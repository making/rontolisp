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
import am.ik.rontolisp.starlark.Starlark;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL Starlark front end ({@code starlark.lisp} on the
 * classpath): the values (list, tuple, dict, range, function), the operators, the call
 * binder, the built-in functions and the methods. It is Common Lisp source like every
 * other shipped library, so no backend learns a Starlark name
 * ({@code .kb/starlark-frontend.md}).
 *
 * <p>
 * Consumers, the {@link UrlLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%starlark-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Starlark program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 */
public final class StarlarkLibrary {

	private static final String NAME_PREFIX = "%STARLARK-";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile Set<String> functionNames;

	private StarlarkLibrary() {
	}

	/**
	 * Returns the parsed library definitions, then those {@link Starlark#runtimeForms}
	 * generates from the front end's tables. The source is written in canonical shape
	 * (internal double-colon {@code rontolisp::} helpers, bare {@code cl} names), so it
	 * needs no package resolution, and it reads the same for every target. Parsed once
	 * and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (StarlarkLibrary.class) {
				cached = forms;
				if (cached == null) {
					List<LispVal> read = new ArrayList<>(
							LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					read.addAll(Starlark.runtimeForms());
					cached = List.copyOf(read);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = StarlarkLibrary.class.getResourceAsStream("starlark.lisp")) {
			if (in == null) {
				throw new IllegalStateException("starlark.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code starlark.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isStarlarkFunction(String symbolName) {
		return mayDefine(symbolName) && functionNames().contains(symbolName);
	}

	/**
	 * Whether the name can be one the library defines, answered from its spelling alone:
	 * every definition is a {@code rontolisp} {@code %starlark-} name. The interpreter
	 * asks {@link #isStarlarkFunction} for every function name it resolves for the first
	 * time and the compile path for every symbol of a program, so any other name must not
	 * read the library ({@code .kb/source-language.md}, "The run-time libraries' name
	 * gate").
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code false} when the library cannot define the name
	 */
	static boolean mayDefine(String symbolName) {
		return symbolName.contains(NAME_PREFIX) && LispSymbol.memberName(symbolName).startsWith(NAME_PREFIX);
	}

	private static Set<String> functionNames() {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			for (String name : names) {
				if (!mayDefine(name)) {
					throw new IllegalStateException("starlark.lisp defines " + name + ", which mayDefine refuses");
				}
			}
			functionNames = names;
		}
		return names;
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
		return rest instanceof LispSymbol symbol && isStarlarkFunction(symbol.name());
	}

}
