package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The {@code rontolisp.internal.*} namespaces of the Clojure lowering: the kernels the
 * built-in namespaces ({@link ClojureBuiltinNamespaces}) call for what Clojure would
 * spell through generic verbs at many times the size, or what needs the run-time
 * library's own state. Each var is one call to its {@code rontolisp::%clojure-} worker in
 * {@code clojure.lisp}, with a fixed arity; a {@code ?} in a name spells {@code -p}. Only
 * a built-in file may require one ({@link ClojureNamespaceLowering#requireOne}); they are
 * no user surface.
 * <ul>
 * <li>{@code rontolisp.internal.ring} for the built-in Ring namespaces: bytes and
 * charsets, the JDK's URL coders, the content-type charset match, the Unicode letter test
 * of {@code wrap-keyword-params}. Measured 2026-10-08, wasm-GC P1:
 * {@code (form-decode-str "a+%41")} written in Clojure inside a namespace was a 340,532 B
 * module, {@code (url-decode "a%41")} 357,787 B (generic {@code conj}, {@code apply str},
 * {@code throw} and the regex engine), where the Common Lisp {@code rontolisp:url-decode}
 * is a 26,940 B module.</li>
 * <li>{@code rontolisp.internal.pprint} for {@code clojure.pprint}: the pretty print's
 * token buffer and its layout, the radix spelling of a number, and the text of
 * {@code cl-format}'s number directives and case conversion.</li>
 * <li>{@code rontolisp.internal.datafy} for {@code clojure.datafy}: the oracle's class
 * name of a value, an exception's too, which {@code class} (a kind keyword here) does not
 * answer.</li>
 * <li>{@code rontolisp.internal.instant} for {@code clojure.instant}: the timestamp
 * match, {@code validated}'s checks and the three readers, which the run-time reader's
 * default {@code #inst} reader shares.</li>
 * <li>{@code rontolisp.internal.uuid} for {@code clojure.uuid}: the run-time reader's
 * default {@code #uuid} reader, which {@code default-data-readers} names as
 * {@code clojure.uuid/default-uuid-reader}.</li>
 * <li>{@code rontolisp.internal.reducers} for {@code clojure.core.reducers}: the
 * accumulator {@code cat} answers (the oracle's {@code java.util.ArrayList}, a growable
 * vector here, which no Clojure verb makes), the push of {@code append!} onto it and the
 * join of two collections into a fresh one.</li>
 * <li>{@code rontolisp.internal.http} for {@code rontolisp.http-client}: the request
 * ({@code clojure.lisp}, "rontolisp.http-client") and {@code fetch}, which is
 * {@code rontolisp:fetch} itself, so the program names the transport every fetch splice
 * reads; the request builds the oracle's exceptions, so a call of it emits the exception
 * runtime.</li>
 * <li>{@code rontolisp.internal.math} for {@code clojure.math}: each double function is
 * one {@code (%strict-math :name ...)} over its arguments cast as the oracle's
 * {@code double} casts them, lowered in place rather than as a worker call -- the
 * oracle's own wrapper is one {@code Math} call; {@code round} and the long arithmetic
 * ({@code floor-div}, the {@code -exact} six) are workers over the oracle's
 * {@code longCast}.</li>
 * </ul>
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureKernelLowering {

	/**
	 * One internal namespace.
	 *
	 * @param owners the built-in namespaces it serves, for the refusal
	 * @param prefix the worker names' prefix
	 * @param arity each var's argument count
	 * @param workers the vars whose worker is no prefixed name, to the one it is
	 * @param exceptions whether a call builds the program's exceptions, so the lowering
	 * emits their runtime
	 * @param inline the vars lowered in place instead of to a worker call
	 */
	record Kernels(String owners, String prefix, Map<String, Integer> arity, Map<String, String> workers,
			boolean exceptions, Map<String, Inline> inline) {

		Kernels(String owners, String prefix, Map<String, Integer> arity) {
			this(owners, prefix, arity, Map.of(), false, Map.of());
		}

		Kernels(String owners, String prefix, Map<String, Integer> arity, Map<String, String> workers,
				boolean exceptions) {
			this(owners, prefix, arity, workers, exceptions, Map.of());
		}

	}

	/** A kernel lowered in place: its form over the lowered arguments. */
	@FunctionalInterface
	interface Inline {

		/**
		 * The kernel's form.
		 * @param args the lowered arguments, as many as its arity
		 * @return the form
		 */
		LispVal lower(List<LispVal> args);

	}

	/**
	 * The double cast of a {@code clojure.math} argument: the oracle's {@code double}.
	 */
	private static final LispSymbol DOUBLE_CAST = new LispSymbol("RONTOLISP::%CLOJURE-DOUBLE");

	/**
	 * The int cast of {@code scalb}'s exponent: the oracle's {@code int} of an object.
	 */
	private static final LispSymbol INT_CAST = new LispSymbol("RONTOLISP::%CLOJURE-INT-CAST");

	/**
	 * The {@code clojure.math} functions that are one {@code java.lang.StrictMath}
	 * method, by name and argument count: {@code (%strict-math :name ...)} names the
	 * method by the same name ({@code compiler.StrictMathFunction}, which the lowering
	 * may not import; {@code ClojureLanguageNamespacesTest} pins the two lists together).
	 */
	static final Map<String, Integer> STRICT_MATH = strictMath();

	private static Map<String, Integer> strictMath() {
		Map<String, Integer> out = new HashMap<>();
		for (String name : List.of("sin", "cos", "tan", "asin", "acos", "atan", "exp", "log", "log10", "sqrt", "cbrt",
				"ceil", "floor", "rint", "sinh", "cosh", "tanh", "expm1", "log1p", "ulp", "signum", "next-up",
				"next-down", "get-exponent")) {
			out.put(name, 1);
		}
		for (String name : List.of("atan2", "pow", "hypot", "IEEE-remainder", "copy-sign", "next-after", "scalb")) {
			out.put(name, 2);
		}
		return Map.copyOf(out);
	}

	/**
	 * {@code rontolisp.internal.math}: the {@link #STRICT_MATH} functions in place, and
	 * the workers ({@code rontolisp::%clojure-math-NAME}) of {@code round} and the long
	 * arithmetic.
	 */
	private static Kernels mathKernels() {
		Map<String, Integer> arity = new HashMap<>(STRICT_MATH);
		Map<String, Inline> inline = new HashMap<>();
		for (String name : STRICT_MATH.keySet()) {
			inline.put(name, args -> strictMathCall(name, args));
		}
		for (String name : List.of("round", "increment-exact", "decrement-exact", "negate-exact")) {
			arity.put(name, 1);
		}
		for (String name : List.of("floor-div", "floor-mod", "add-exact", "subtract-exact", "multiply-exact")) {
			arity.put(name, 2);
		}
		return new Kernels("clojure.math", "RONTOLISP::%CLOJURE-MATH-", Map.copyOf(arity), Map.of(), false,
				Map.copyOf(inline));
	}

	/**
	 * {@code (%strict-math :name (double a) ...)}: the arguments cast to doubles, and
	 * {@code scalb}'s exponent to an int, as the oracle's wrapper casts them.
	 */
	private static LispVal strictMathCall(String name, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(LispNames.STRICT_MATH_INTERNAL));
		call.add(new LispSymbol(":" + name.toUpperCase(Locale.ROOT)));
		for (int i = 0; i < args.size(); i++) {
			boolean exponent = name.equals("scalb") && i == 1;
			call.add(ClojureLowerUtil.list(exponent ? INT_CAST : DOUBLE_CAST, args.get(i)));
		}
		return ClojureLowerUtil.list(call);
	}

	/**
	 * The worker of {@code rontolisp.internal.http/request}: what makes a fetch family
	 * value.
	 */
	static final String HTTP_REQUEST = "RONTOLISP::%CLOJURE-HTTP-REQUEST";

	/** The internal namespaces. */
	private static final Map<String, Kernels> NAMESPACES = Map.of("rontolisp.internal.ring",
			new Kernels("the built-in Ring namespaces", "RONTOLISP::%CLOJURE-RING-",
					Map.ofEntries(Map.entry("percent-encode", 2), Map.entry("percent-decode", 2),
							Map.entry("url-encode", 2), Map.entry("form-encode", 2), Map.entry("form-decode-str", 2),
							Map.entry("form-decode-map", 2), Map.entry("form-decode", 2), Map.entry("parse-long", 1),
							Map.entry("content-type-charset", 1), Map.entry("keyword-syntax?", 2))),
			"rontolisp.internal.pprint",
			new Kernels("clojure.pprint", "RONTOLISP::%CLOJURE-PP-",
					Map.ofEntries(Map.entry("call", 3), Map.entry("start", 4), Map.entry("end", 1),
							Map.entry("newline", 1), Map.entry("indent", 2), Map.entry("fresh-line", 0),
							Map.entry("length-reached", 1), Map.entry("count-object", 0), Map.entry("reset-length", 0),
							Map.entry("number-string", 3), Map.entry("members", 1), Map.entry("active?", 0),
							Map.entry("tab", 3), Map.entry("eol", 3), Map.entry("padding", 2),
							Map.entry("integer-text", 10), Map.entry("english", 4), Map.entry("roman", 4),
							Map.entry("fixed", 7), Map.entry("exponential", 9), Map.entry("general", 9),
							Map.entry("dollar", 7), Map.entry("case-state", 0), Map.entry("case-convert", 3),
							Map.entry("case-out", 0), Map.entry("with-case-out", 2))),
			"rontolisp.internal.datafy",
			new Kernels("clojure.datafy", "RONTOLISP::%CLOJURE-DATAFY-", Map.of("class-name", 1)),
			"rontolisp.internal.instant",
			new Kernels("clojure.instant", "RONTOLISP::%CLOJURE-INSTANT-",
					Map.ofEntries(Map.entry("parse", 1), Map.entry("validate", 10), Map.entry("read-date", 1),
							Map.entry("read-timestamp", 1), Map.entry("read-calendar", 1))),
			"rontolisp.internal.uuid", new Kernels("clojure.uuid", "RONTOLISP::%CLOJURE-", Map.of("read-uuid", 1)),
			"rontolisp.internal.reducers",
			new Kernels("clojure.core.reducers", "RONTOLISP::%CLOJURE-REDUCERS-",
					Map.ofEntries(Map.entry("accumulator", 0), Map.entry("accumulator?", 1), Map.entry("append", 2),
							Map.entry("joined", 2))),
			"rontolisp.internal.http",
			new Kernels("rontolisp.http-client", "RONTOLISP::%CLOJURE-HTTP-",
					Map.ofEntries(Map.entry("request", 2), Map.entry("fetch", 2)), Map.of("fetch", "RONTOLISP:FETCH"),
					true),
			"rontolisp.internal.math", mathKernels());

	private ClojureKernelLowering() {
	}

	/**
	 * Whether the namespace is an internal one.
	 * @param ns the namespace
	 * @return whether its vars are kernels
	 */
	static boolean isKernelNamespace(String ns) {
		return NAMESPACES.containsKey(ns);
	}

	/**
	 * Whether the internal namespace has the var.
	 * @param ns the namespace
	 * @param var the var name
	 * @return whether it is one of its kernels
	 */
	static boolean isKernelVar(String ns, String var) {
		Kernels kernels = NAMESPACES.get(ns);
		return kernels != null && kernels.arity().containsKey(var);
	}

	/**
	 * The vars of an internal namespace.
	 * @param ns the namespace
	 * @return its vars, or {@code null} for any other namespace
	 */
	static @Nullable Set<String> varsOf(String ns) {
		Kernels kernels = NAMESPACES.get(ns);
		return kernels == null ? null : kernels.arity().keySet();
	}

	/**
	 * Why a file that is no built-in one may not require the internal namespace.
	 * @param ns the namespace
	 * @return the refusal
	 */
	static String refusal(String ns) {
		Kernels kernels = NAMESPACES.get(ns);
		return ns + " is internal to " + (kernels == null ? "the built-in namespaces" : kernels.owners());
	}

	/**
	 * A kernel call: the worker over the lowered arguments.
	 * @param ctx the hub
	 * @param ns the internal namespace
	 * @param var the var name
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal kernelCall(ClojureLowering ctx, String ns, String var, List<LispVal> items) {
		Kernels kernels = NAMESPACES.get(ns);
		Integer arity = kernels == null ? null : kernels.arity().get(var);
		if (kernels == null || arity == null) {
			throw new LispReadException("unknown name: " + ns + "/" + var);
		}
		if (items.size() - 1 != arity) {
			throw new LispReadException(
					"Wrong number of args (" + (items.size() - 1) + ") passed to: " + ns + "/" + var);
		}
		ctx.usedExInfo |= kernels.exceptions();
		Inline inline = kernels.inline().get(var);
		if (inline != null) {
			return inline.lower(ctx.lowers(items, 1));
		}
		return ClojureLowerUtil.cons(worker(kernels, var), ctx.lowers(items, 1));
	}

	/**
	 * A kernel as a function value: the worker itself.
	 * @param ctx the hub
	 * @param ns the internal namespace
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal kernelValue(ClojureLowering ctx, String ns, String var) {
		Kernels kernels = NAMESPACES.get(ns);
		if (kernels == null || !kernels.arity().containsKey(var)) {
			throw new LispReadException("unknown name: " + ns + "/" + var);
		}
		ctx.usedExInfo |= kernels.exceptions();
		Inline inline = kernels.inline().get(var);
		if (inline != null) {
			// A lambda of the kernel's arity around its in-place form.
			List<LispVal> params = new ArrayList<>();
			for (int i = 0; i < kernels.arity().get(var); i++) {
				params.add(new LispSymbol("ARG" + i + "%"));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(params), inline.lower(params)));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), worker(kernels, var));
	}

	private static LispSymbol worker(Kernels kernels, String var) {
		String own = kernels.workers().get(var);
		if (own != null) {
			return new LispSymbol(own);
		}
		String name = var.endsWith("?") ? var.substring(0, var.length() - 1) + "-p" : var;
		return new LispSymbol(kernels.prefix() + name.toUpperCase(Locale.ROOT));
	}

}
