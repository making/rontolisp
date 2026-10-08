package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * token buffer and its layout, and the radix spelling of a number.</li>
 * <li>{@code rontolisp.internal.datafy} for {@code clojure.datafy}: the oracle's class
 * name of a value, which {@code class} (a kind keyword here) does not answer.</li>
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
	 */
	record Kernels(String owners, String prefix, Map<String, Integer> arity) {
	}

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
							Map.entry("number-string", 3), Map.entry("members", 1))),
			"rontolisp.internal.datafy",
			new Kernels("clojure.datafy", "RONTOLISP::%CLOJURE-", Map.of("class-name-of", 1)));

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
		return ClojureLowerUtil.cons(worker(kernels, var), ctx.lowers(items, 1));
	}

	/**
	 * A kernel as a function value: the worker itself.
	 * @param ns the internal namespace
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal kernelValue(String ns, String var) {
		Kernels kernels = NAMESPACES.get(ns);
		if (kernels == null || !kernels.arity().containsKey(var)) {
			throw new LispReadException("unknown name: " + ns + "/" + var);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), worker(kernels, var));
	}

	private static LispSymbol worker(Kernels kernels, String var) {
		String name = var.endsWith("?") ? var.substring(0, var.length() - 1) + "-p" : var;
		return new LispSymbol(kernels.prefix() + name.toUpperCase(Locale.ROOT));
	}

}
