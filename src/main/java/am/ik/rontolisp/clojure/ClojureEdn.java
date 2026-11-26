package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * A datum read as data printed back the way the oracle's {@code pr-str} prints it, for
 * the refusals that quote one in the oracle's words: a map as {@code {:k v, :k v}},
 * lifted to {@code #:ns{:k v}} when every key is a qualified keyword or symbol of one
 * namespace (the oracle's default {@code *print-namespace-maps*}), a vector, a set, a
 * string with its escapes, a character by its name ({@code \\space}), a double as the
 * oracle prints one ({@code 1.0E10}, {@code ##NaN}), a tagged literal as
 * {@code #tag form}. What it prints reads back as the same datum, so a generated program
 * may carry one as source ({@link ClojureMain}).
 */
final class ClojureEdn {

	private ClojureEdn() {
	}

	/**
	 * Prints a datum.
	 * @param datum what the reader read
	 * @return its printed form
	 */
	static String print(LispVal datum) {
		StringBuilder out = new StringBuilder();
		print(datum, out);
		return out.toString();
	}

	private static void print(LispVal datum, StringBuilder out) {
		if (datum instanceof LispString string) {
			printString(string.value(), out);
			return;
		}
		if (datum instanceof LispSymbol symbol) {
			out.append(symbol.name());
			return;
		}
		if (datum instanceof LispNil) {
			out.append("()");
			return;
		}
		if (datum instanceof LispChar character) {
			out.append('\\').append(switch (character.codePoint()) {
				case ' ' -> "space";
				case '\n' -> "newline";
				case '\t' -> "tab";
				case '\r' -> "return";
				case '\b' -> "backspace";
				case '\f' -> "formfeed";
				default -> Character.toString(character.codePoint());
			});
			return;
		}
		if (datum instanceof LispDouble number) {
			double value = number.value();
			out.append(Double.isNaN(value) ? "##NaN"
					: Double.isInfinite(value) ? (value > 0 ? "##Inf" : "##-Inf") : Double.toString(value));
			return;
		}
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items == null || items.isEmpty()) {
			out.append(datum.print());
			return;
		}
		LispVal head = items.get(0);
		List<LispVal> rest = items.subList(1, items.size());
		if (ClojureLowerUtil.isSymbolNamed(head, "%hash-map")) {
			printMap(rest, out);
		}
		else if (head == ClojureReader.VECTOR) {
			printSequence("[", rest, out, "]");
		}
		else if (ClojureLowerUtil.isSymbolNamed(head, "%hash-set")) {
			printSequence("#{", rest, out, "}");
		}
		else if (head == ClojureReader.TAGGED && rest.size() == 2) {
			out.append('#');
			print(rest.get(0), out);
			out.append(' ');
			print(rest.get(1), out);
		}
		else {
			printSequence("(", items, out, ")");
		}
	}

	private static void printSequence(String open, List<LispVal> items, StringBuilder out, String close) {
		out.append(open);
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				out.append(' ');
			}
			print(items.get(i), out);
		}
		out.append(close);
	}

	private static void printMap(List<LispVal> kvs, StringBuilder out) {
		String ns = liftedNamespace(kvs);
		out.append(ns == null ? "{" : "#:" + ns + "{");
		for (int i = 0; i + 1 < kvs.size(); i += 2) {
			if (i > 0) {
				out.append(", ");
			}
			LispVal key = kvs.get(i);
			if (ns != null) {
				String name = ((LispSymbol) key).name();
				boolean keyword = name.startsWith(":");
				out.append(keyword ? ":" : "").append(name.substring(name.indexOf('/') + 1));
			}
			else {
				print(key, out);
			}
			out.append(' ');
			print(kvs.get(i + 1), out);
		}
		out.append('}');
	}

	/**
	 * The namespace every key shares when each is a qualified keyword or symbol, the
	 * oracle's {@code lift-ns}; null otherwise, and for an empty map.
	 */
	private static @Nullable String liftedNamespace(List<LispVal> kvs) {
		String shared = null;
		for (int i = 0; i + 1 < kvs.size(); i += 2) {
			if (!(kvs.get(i) instanceof LispSymbol key)) {
				return null;
			}
			String name = key.name().startsWith(":") ? key.name().substring(1) : key.name();
			int slash = name.indexOf('/');
			if (slash <= 0 || slash == name.length() - 1) {
				return null;
			}
			String ns = name.substring(0, slash);
			if (shared != null && !shared.equals(ns)) {
				return null;
			}
			shared = ns;
		}
		return shared;
	}

	private static void printString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\t' -> out.append("\\t");
				case '\r' -> out.append("\\r");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> out.append(c);
			}
		}
		out.append('"');
	}

}
