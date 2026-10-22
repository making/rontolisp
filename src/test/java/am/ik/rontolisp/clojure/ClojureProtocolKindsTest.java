package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureLibrary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-time class-name tables behind the protocol value arms ({@code clojure.lisp},
 * {@code %clojure-extends-kind-names}, {@code %clojure-extends-time-names},
 * {@code %clojure-extends-io-names}, {@code %clojure-extends-reaches} and
 * {@code %clojure-extends-dotless-names}) mirror the lowering's static maps entry for
 * entry, so a run-time class object or designator reads back as the keyword a class
 * spelling stores. {@code java.lang.Object} stays out of the kind tables: the lowering
 * special-cases it to the default, and the key function tests the full name.
 */
class ClojureProtocolKindsTest {

	@Test
	void theRunTimeTablesMirrorTheLoweringMaps() {
		Map<String, List<LispVal>> tables = tables();
		assertThat(names(required(tables, "RONTOLISP::%CLOJURE-EXTENDS-KIND-NAMES")))
			.containsExactlyInAnyOrderEntriesOf(ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS);
		assertThat(names(required(tables, "RONTOLISP::%CLOJURE-EXTENDS-TIME-NAMES")))
			.containsExactlyInAnyOrderEntriesOf(ClojureClassBases.TIME_VALUE_DISPATCH);
		assertThat(strings(required(tables, "RONTOLISP::%CLOJURE-EXTENDS-IO-NAMES")))
			.containsExactlyInAnyOrderElementsOf(ClojureIoLowering.EXTENDABLE);
		assertThat(strings(required(tables, "RONTOLISP::%CLOJURE-EXTENDS-REACHES")))
			.containsExactlyInAnyOrderElementsOf(reaches());
		assertThat(names(required(tables, "RONTOLISP::%CLOJURE-EXTENDS-DOTLESS-NAMES")))
			.containsExactlyInAnyOrderEntriesOf(dotless());
	}

	private static List<LispVal> required(Map<String, List<LispVal>> tables, String name) {
		assertThat(tables).containsKey(name);
		return Objects.requireNonNull(tables.get(name));
	}

	/**
	 * The resolved class names a kind-spelled target keys by its own name instead of the
	 * kind's: an interface values of other kinds implement too, or an instant class --
	 * what {@code extendKeyForm}'s {@code reachesOtherKinds} answers over the lowering's
	 * static tables.
	 * @return the full names, sorted
	 */
	private static Set<String> reaches() {
		Set<String> names = new TreeSet<>();
		for (Map.Entry<String, String> entry : ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS.entrySet()) {
			for (String prefix : List.of("java.lang.", "clojure.lang.", "java.util.")) {
				String fqn = prefix + entry.getKey();
				if (reaches(fqn, entry.getValue())) {
					names.add(fqn);
				}
			}
		}
		for (Map.Entry<String, String> entry : ClojureClassBases.TIME_VALUE_DISPATCH.entrySet()) {
			if (reaches(entry.getKey(), entry.getValue())) {
				names.add(entry.getKey());
			}
		}
		return names;
	}

	/**
	 * Whether a resolved class name reaches other kinds: one of its kinds is not a
	 * record, deftype or reify and dispatches under another keyword.
	 * @param fqn the resolved class name
	 * @param kind the kind keyword a class spelling stores under
	 * @return whether the lowering keys the target by its own name
	 */
	private static boolean reaches(String fqn, String kind) {
		for (ClojureValueClasses.Kind one : ClojureValueClasses.kindsOf(fqn)) {
			boolean typed = one == ClojureValueClasses.Kind.RECORD || one == ClojureValueClasses.Kind.DEFTYPE
					|| one == ClojureValueClasses.Kind.REIFY;
			if (!typed && !kind.equals(ClojureValueClasses.dispatchKeyword(one))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The dotless spellings resolving to a {@code clojure.lang} class the kind tables
	 * name: the lowering's {@code clojureLang} leniency over the dispatch class keywords.
	 * @return the spelling to class-name entries
	 */
	private static Map<String, String> dotless() {
		Map<String, String> names = new LinkedHashMap<>();
		for (String simple : new TreeSet<>(ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS.keySet())) {
			String full = ClojureValueClasses.clojureLang(simple);
			if (full != null) {
				names.put(simple, full);
			}
		}
		return names;
	}

	private static Map<String, List<LispVal>> tables() {
		Map<String, List<LispVal>> tables = new LinkedHashMap<>();
		for (LispVal form : ClojureLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFVAR")
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& name.name().startsWith("RONTOLISP::%CLOJURE-EXTENDS-") && rest.cdr() instanceof LispCons value
					&& value.car() instanceof LispCons quote && quote.car() instanceof LispSymbol quoted
					&& quoted.name().equals("QUOTE") && quote.cdr() instanceof LispCons quotedList) {
				tables.put(name.name(), conses(quotedList.car()));
			}
		}
		return tables;
	}

	private static Map<String, String> names(List<LispVal> quoted) {
		Map<String, String> names = new LinkedHashMap<>();
		for (LispVal entry : quoted) {
			LispCons pair = (LispCons) entry;
			names.put(((LispString) pair.car()).value(), ((LispString) pair.cdr()).value());
		}
		return names;
	}

	private static Set<String> strings(List<LispVal> quoted) {
		Set<String> names = new LinkedHashSet<>();
		for (LispVal entry : quoted) {
			names.add(((LispString) entry).value());
		}
		return names;
	}

	private static List<LispVal> conses(LispVal list) {
		List<LispVal> out = new ArrayList<>();
		while (list instanceof LispCons cons) {
			out.add(cons.car());
			list = cons.cdr();
		}
		return out;
	}

}
