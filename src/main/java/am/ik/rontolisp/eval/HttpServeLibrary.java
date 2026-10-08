package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * "Serve this application on the target's native inbound transport"
 * ({@code http-serve.lisp}): {@code rontolisp::%http-serve} and
 * {@code rontolisp::%http-serve-stop}, the transport legs written once for every adapter
 * that offers them -- the {@code clack-handler-rontolisp} shim's {@code run}/{@code stop}
 * and the Clojure front end's {@code ring.adapter.rontolisp/run-server}
 * ({@code .kb/clack.md}, "Transport selection").
 *
 * <p>
 * The source branches on reader features, so it is read with the TARGET's features, like
 * a shim ({@link ShimLibraries#forms}): a socket server on the interpreter and the JVM,
 * the Servlet registration under {@code #+rontolisp-servlet}, the
 * {@code rontolisp:http-handler} directive on WASI WASM, the reactor store and marker
 * under {@code #+rontolisp-reactor}. Consumers:
 * <ul>
 * <li>the interpreter loads {@link #forms(Features)} with its own features on the first
 * resolution of one of the library's functions ({@link #isServeFunction}), ahead of the
 * broader {@code RONTOLISP::%HTTP-} hook that loads {@code http-server.lisp};</li>
 * <li>the compile path calls {@link #process(List, Features)} first in
 * {@code CompileFrontend.expand}, BEFORE every pass that reads what a leg contains: the
 * {@code http-handler} directive detection and splice ({@code HttpHandlerInliner},
 * {@code HttpLibrary}), the reactor marker ({@code HttpReactorInliner}) and the
 * {@code :raw-body} mode scan.</li>
 * </ul>
 */
public final class HttpServeLibrary {

	/** The serve entry point: {@code (app port address join)}. */
	public static final String SERVE = "RONTOLISP::%HTTP-SERVE";

	/** The stop entry point: {@code (server)}. */
	public static final String STOP = "RONTOLISP::%HTTP-SERVE-STOP";

	private static final Map<String, List<LispVal>> FORMS = new ConcurrentHashMap<>();

	private HttpServeLibrary() {
	}

	/**
	 * Returns the library definitions read with the given features. The source is written
	 * in canonical shape (internal double-colon names, bare {@code cl} names), so it
	 * needs no package resolution. Parsed once per feature set.
	 * @param features the target's reader features
	 * @return the library forms
	 */
	public static List<LispVal> forms(Features features) {
		return FORMS.computeIfAbsent(String.join(",", features.names()),
				ignored -> List.copyOf(LispReader.readAllFromString(readSource(), features)));
	}

	private static String readSource() {
		try (InputStream in = HttpServeLibrary.class.getResourceAsStream("http-serve.lisp")) {
			if (in == null) {
				throw new IllegalStateException("http-serve.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the two entry points the interpreter loads the
	 * library for.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} for {@link #SERVE} and {@link #STOP}
	 */
	public static boolean isServeFunction(String symbolName) {
		return SERVE.equals(symbolName) || STOP.equals(symbolName);
	}

	/**
	 * The compile-path pre-pass: prepends the library, read with the target's features,
	 * when the program names one of its entry points and defines neither. Any other
	 * program is returned unchanged.
	 * @param program the top-level forms
	 * @param features the target's reader features
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, Features features) {
		Set<String> defined = new HashSet<>();
		boolean referenced = false;
		for (LispVal form : program) {
			String name = definedName(form);
			if (name != null) {
				defined.add(name);
			}
			referenced = referenced || references(form);
		}
		if (!referenced || defined.contains(SERVE) || defined.contains(STOP)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms(features));
		out.addAll(program);
		return out;
	}

	private static @Nullable String definedName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFUN".equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isServeFunction(symbol.name());
	}

}
