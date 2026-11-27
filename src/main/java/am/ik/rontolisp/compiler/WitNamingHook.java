package am.ik.rontolisp.compiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;

/**
 * The {@code :names} option both WIT directives take: the naming hook of a front end that
 * spells its identifiers its own way ({@code rontolisp.wit} of the Clojure lowering,
 * whose vars are {@code c%ns/name} symbols). {@link WitImportDirective} binds each member
 * it lists under the Lisp name it gives; {@link WitExportDirective} implements each world
 * export by the function it gives. It names the Lisp side only -- the WIT labels stay the
 * provider's member names, the host fields and the component's export names -- so it is
 * no per-function alias of the WIT.
 */
final class WitNamingHook {

	private WitNamingHook() {
	}

	/**
	 * Parses a {@code :names} table, {@code (("label" "lisp-name") ...)}: both strings,
	 * so no package resolution touches either, in the order written (a
	 * {@link java.util.SequencedMap}, so a lowering over it is deterministic).
	 * @param value the option's value
	 * @param directive the directive's name, for a message
	 * @param form the directive form, for a message
	 * @return the table
	 * @throws UnsupportedOperationException when the value is no such table, or names a
	 * label twice
	 */
	static Map<String, String> parse(LispVal value, String directive, LispCons form) {
		Map<String, String> names = new LinkedHashMap<>();
		LispVal rest = value;
		while (rest instanceof LispCons cons) {
			if (!(cons.car() instanceof LispCons entry) || !(entry.car() instanceof LispString label)
					|| !(entry.cdr() instanceof LispCons tail) || !(tail.car() instanceof LispString lispName)
					|| !(tail.cdr() instanceof LispNil)) {
				throw new UnsupportedOperationException(directive
						+ " :names expects a list of (\"label\" \"lisp-name\") string pairs in " + form.print());
			}
			if (names.put(label.value(), lispName.value()) != null) {
				throw new UnsupportedOperationException(
						directive + " :names names '" + label.value() + "' twice in " + form.print());
			}
			rest = cons.cdr();
		}
		if (!(rest instanceof LispNil)) {
			throw new UnsupportedOperationException(
					directive + " :names expects a list of (\"label\" \"lisp-name\") string pairs in " + form.print());
		}
		return Collections.unmodifiableMap(names);
	}

	/**
	 * The Lisp name a WIT function has before any table renames it, without a package
	 * qualifier: a freestanding function keeps its WIT label; a {@code resource} member
	 * is prefixed with the resource ({@code bucket.get} -> {@code bucket-get},
	 * {@code constructor(bucket)} -> {@code bucket-new}). One spelling for both
	 * directions: what an import of the interface binds is what a program exporting it
	 * defines.
	 * @param func the WIT function and its owning resource
	 * @return the bare Lisp function name
	 */
	static String memberName(am.ik.wit.WitResolver.Func func) {
		String resource = func.resource();
		if (resource == null) {
			return func.def().name();
		}
		return switch (func.def().kind()) {
			case CONSTRUCTOR -> resource + "-new";
			case PLAIN, STATIC -> resource + "-" + func.def().name();
		};
	}

}
