package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Shared shape helpers behind the Clojure lowering: pure static builders with no lowering
 * state.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureLowerUtil {

	private ClojureLowerUtil() {
	}

	/**
	 * Whether the function form is already a real function: a {@code function} designator
	 * or a lambda. Anything else (a variable, a call result) may hold a collection at run
	 * time and goes through the dispatcher instead.
	 */
	static boolean isDirectFun(LispVal fun) {
		if (!(fun instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		return head.name().equals("FUNCTION") || head.name().equals("LAMBDA");
	}

	/** {@code (LET bindings body...)}: a let over a computed body, spliced flat. */
	static LispVal letForm(List<LispVal> bindings, List<LispVal> body) {
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(bindings));
		forms.addAll(body);
		return cons(sym("let"), forms);
	}

	/** {@code (QUOTE name)} over a lower-case name, for a coerce designator. */
	static LispVal quoted(String name) {
		return list(sym("quote"), sym(name));
	}

	static String plainName(LispVal val, String what) {
		val = stripMeta(val);
		if (val instanceof LispSymbol s && !s.name().startsWith(":") && !s.name().equals("&")) {
			return s.name();
		}
		throw new LispReadException(what + " needs a plain name, not " + val.print());
	}

	/**
	 * A datum with its {@code ^...} metadata dropped: the reader spells
	 * {@code ^:private x} as {@code (with-meta x :private)}, and metadata never affects
	 * dispatch, so every name position unwraps it.
	 */
	static LispVal stripMeta(LispVal datum) {
		List<LispVal> parts = items(datum);
		while (parts != null && parts.size() == 3 && isSymbolNamed(parts.get(0), "with-meta")) {
			datum = parts.get(1);
			parts = items(datum);
		}
		return datum;
	}

	/**
	 * Whether a name datum carries {@code ^:dynamic} metadata (or {@code ^{:dynamic
	 * true}}), under any wrapping.
	 */
	static boolean nameIsDynamic(LispVal nameDatum) {
		return nameHasFlag(nameDatum, ":dynamic");
	}

	/**
	 * Whether a name datum carries {@code ^:private} metadata (or {@code ^{:private
	 * true}}), under any wrapping.
	 */
	static boolean nameIsPrivate(LispVal nameDatum) {
		return nameHasFlag(nameDatum, ":private");
	}

	/**
	 * Whether a name datum carries the flag keyword (spelled with its colon) in any of
	 * its {@code ^...} metadata, under any wrapping.
	 */
	static boolean nameHasFlag(LispVal nameDatum, String flag) {
		List<LispVal> parts = items(nameDatum);
		while (parts != null && parts.size() == 3 && isSymbolNamed(parts.get(0), "with-meta")) {
			if (metaHasFlag(parts.get(2), flag)) {
				return true;
			}
			nameDatum = parts.get(1);
			parts = items(nameDatum);
		}
		return false;
	}

	/**
	 * Whether a {@code ^...} metadata datum sets a flag: the bare keyword, or an attr map
	 * holding it with a value other than {@code false}/{@code nil}.
	 */
	private static boolean metaHasFlag(LispVal meta, String flag) {
		if (meta instanceof LispSymbol s) {
			return s.name().equals(flag);
		}
		List<LispVal> parts = items(meta);
		if (parts == null || parts.isEmpty() || !isSymbolNamed(parts.get(0), "%hash-map")) {
			return false;
		}
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispSymbol k && k.name().equals(flag)) {
				LispVal value = parts.get(i + 1);
				return !(isSymbolNamed(value, "false") || isSymbolNamed(value, "nil"));
			}
		}
		return false;
	}

	/**
	 * Whether a lowered form mentions the symbol anywhere (quoted data included, which
	 * only costs an unneeded binding).
	 */
	static boolean mentions(LispVal form, String symbolName) {
		LispVal at = form;
		while (at instanceof LispCons cons) {
			if (mentions(cons.car(), symbolName)) {
				return true;
			}
			at = cons.cdr();
		}
		return at instanceof LispSymbol s && s.name().equals(symbolName);
	}

	/** The items of a {@code [...]} vector datum, without its marker. */
	static List<LispVal> bindingItems(LispVal vector, String what) {
		List<LispVal> items = items(vector);
		if (items == null || items.isEmpty() || items.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes its bindings in a vector: " + vector.print());
		}
		return items.subList(1, items.size());
	}

	static boolean isSymbolNamed(LispVal val, String name) {
		return val instanceof LispSymbol s && s.name().equals(name);
	}

	static boolean isNsForm(LispVal val) {
		if (isSymbolNamed(val, "ns")) {
			return true;
		}
		List<LispVal> items = items(val);
		return items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "ns");
	}

	static List<LispVal> items(LispVal val, List<LispVal> ifNone) {
		List<LispVal> items = items(val);
		return items == null ? ifNone : items;
	}

	static @Nullable List<LispVal> items(LispVal val) {
		if (val instanceof LispNil) {
			return List.of();
		}
		if (!(val instanceof LispCons)) {
			return null;
		}
		List<LispVal> out = new ArrayList<>();
		LispVal run = val;
		while (run instanceof LispCons cons) {
			out.add(cons.car());
			run = cons.cdr();
		}
		if (!(run instanceof LispNil)) {
			return null;
		}
		return out;
	}

	static LispVal cons(LispVal car, List<LispVal> rest) {
		return new LispCons(car, list(rest));
	}

	static LispSymbol sym(String name) {
		return new LispSymbol(name.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * A user identifier as a symbol: mangled behind the prefix but otherwise
	 * case-preserved, so {@code Foo} and {@code foo} stay apart. The prefix holds a
	 * lowercase letter, so no result can collide with a core form or built-in. A
	 * {@code #:}-spelled gensym a macro expansion returned travels as itself, so it
	 * lowers back to the same uninterned symbol. Two dynamic vars are aliases, not
	 * mangled names: {@code *out*} is {@code *standard-output*} (the stream the print
	 * family writes to, already special on every backend), and {@code *agent*} is the
	 * agent var the STM runtime binds while a {@code send} runs.
	 */
	static LispSymbol idSym(String identifier) {
		if (identifier.startsWith("#:")) {
			return new LispSymbol(identifier);
		}
		if (identifier.equals("*out*")) {
			return new LispSymbol("*STANDARD-OUTPUT*");
		}
		if (identifier.equals("*in*")) {
			return new LispSymbol("*STANDARD-INPUT*");
		}
		if (identifier.equals("*agent*")) {
			return new LispSymbol("C%AGENT");
		}
		return new LispSymbol(ClojureLowering.mangle(identifier));
	}

	static LispVal list(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

	static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

	static void isTrue(boolean ok, String message) {
		if (!ok) {
			throw new LispReadException(message);
		}
	}

}
