package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code rontolisp.internal.ring} of the Clojure lowering: the kernels the built-in Ring
 * namespaces ({@link ClojureBuiltinNamespaces}) call for what Clojure would spell through
 * generic verbs -- bytes and charsets, the JDK's URL coders, the content-type charset
 * match, the Unicode letter test of {@code wrap-keyword-params}. Each var is one call to
 * its {@code rontolisp::%clojure-ring-} worker in {@code clojure.lisp}, with a fixed
 * arity. Only a built-in file may require the namespace
 * ({@link ClojureNamespaceLowering#requireOne}); it is no user surface.
 *
 * <p>
 * Measured 2026-10-08, wasm-GC P1: {@code (form-decode-str "a+%41")} written in Clojure
 * inside a namespace was a 340,532 B module, {@code (url-decode "a%41")} 357,787 B
 * (generic {@code conj}, {@code apply str}, {@code throw} and the regex engine), where
 * the Common Lisp {@code rontolisp:url-decode} is a 26,940 B module.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureRingUtilLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "rontolisp.internal.ring";

	/** Each var's argument count. */
	private static final Map<String, Integer> ARITY = Map.ofEntries(Map.entry("percent-encode", 2),
			Map.entry("percent-decode", 2), Map.entry("url-encode", 2), Map.entry("form-encode", 2),
			Map.entry("form-decode-str", 2), Map.entry("form-decode-map", 2), Map.entry("form-decode", 2),
			Map.entry("parse-long", 1), Map.entry("content-type-charset", 1), Map.entry("keyword-syntax?", 2));

	/** The vars. */
	static final Set<String> VARS = ARITY.keySet();

	private ClojureRingUtilLowering() {
	}

	/**
	 * A kernel call: the worker over the lowered arguments.
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal kernelCall(ClojureLowering ctx, String var, List<LispVal> items) {
		Integer arity = ARITY.get(var);
		if (arity == null) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		if (items.size() - 1 != arity) {
			throw new LispReadException(
					"Wrong number of args (" + (items.size() - 1) + ") passed to: " + NAMESPACE + "/" + var);
		}
		return ClojureLowerUtil.cons(worker(var), ctx.lowers(items, 1));
	}

	/**
	 * A kernel as a function value: the worker itself.
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal kernelValue(String var) {
		if (!ARITY.containsKey(var)) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), worker(var));
	}

	private static LispSymbol worker(String var) {
		String name = var.endsWith("?") ? var.substring(0, var.length() - 1) + "-p" : var;
		return new LispSymbol("RONTOLISP::%CLOJURE-RING-" + name.toUpperCase(Locale.ROOT));
	}

}
