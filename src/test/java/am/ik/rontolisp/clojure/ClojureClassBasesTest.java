package am.ik.rontolisp.clojure;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bases a class keyword's rows hold: the tables answer on hosts that cannot reflect
 * these classes, so on this one every {@code java} row must say what reflection says.
 */
class ClojureClassBasesTest {

	@Test
	void everyJavaRowIsTheHostsSuperclassThenItsInterfaces() throws ClassNotFoundException {
		List<String> names = new ArrayList<>(ClojureThrowables.PARENTS.keySet());
		names.addAll(ClojureClassBases.STREAM_SUPERS.keySet());
		names.addAll(ClojureClassBases.STREAM_SUPERS.values());
		names.addAll(ClojureClassBases.IO_SUPERS.keySet());
		names.addAll(ClojureClassBases.TABLED_INTERFACES);
		names.addAll(ClojureClassBases.TIME_VALUE_DISPATCH.keySet());
		names.addAll(ClojureClassBases.NUMBER_SUPERS.keySet());
		names.add(ClojureClassBases.NUMBER);
		names.addAll(List.of(ClojureThrowables.THROWABLE, ClojureClassBases.OBJECT));
		for (String name : names) {
			if (name.startsWith("java.")) {
				assertThat(ClojureClassBases.basesOf(name)).as(name).isEqualTo(reflected(name));
			}
		}
	}

	@Test
	void theSupersOfAClassAreEveryBaseAndItsSupers() {
		assertThat(ClojureClassBases.supersOf("java.lang.NumberFormatException")).containsExactlyInAnyOrder(
				"java.lang.IllegalArgumentException", "java.lang.RuntimeException", "java.lang.Exception",
				"java.lang.Throwable", "java.lang.Object", "java.io.Serializable");
		// clojure.lang is not on this class path: the oracle's bases (clj 1.12.6)
		assertThat(ClojureClassBases.basesOf("clojure.lang.ExceptionInfo"))
			.containsExactly("java.lang.RuntimeException", "clojure.lang.IExceptionInfo");
		assertThat(ClojureClassBases.supersOf("java.io.StringWriter"))
			.isEqualTo(Set.of("java.io.Writer", "java.lang.Object", "java.lang.Appendable", "java.io.Closeable",
					"java.lang.AutoCloseable", "java.io.Flushable"));
		// the instants' and the UUID's classes are tabled: a Timestamp walks to Date
		assertThat(ClojureClassBases.supersOf("java.sql.Timestamp")).isEqualTo(Set.of("java.util.Date",
				"java.lang.Object", "java.io.Serializable", "java.lang.Cloneable", "java.lang.Comparable"));
		assertThat(ClojureClassBases.timeValueSubclassesOf("java.util.Date")).containsExactly("java.sql.Timestamp");
		assertThat(ClojureClassBases.timeValueSubclassesOf("java.lang.Object")).isEmpty();
		// beyond the tables, a throwable reflects; any other class is no class keyword
		assertThat(ClojureClassBases.basesOf("java.io.FileNotFoundException")).containsExactly("java.io.IOException");
		assertThat(ClojureClassBases.basesOf("java.util.ArrayList")).isNull();
	}

	/**
	 * The table {@code supers} and {@code bases} read for a designator
	 * ({@code clojure.lisp}, {@code %clojure-class-bases-text}): a JDK class's line is
	 * what reflection says on this host, a {@code clojure.lang} class's what its stub row
	 * ({@code %clojure-lang-classes}) says, every stub row has a line, and every base a
	 * line names has a line of its own, so a walk never meets a class it cannot answer.
	 */
	@Test
	void theDesignatorTableIsReflectionsAndTheStubRowsClosedUnderTheirBases() throws Exception {
		String library;
		try (InputStream in = LispEvaluator.class.getResourceAsStream("clojure.lisp")) {
			assertThat(in).isNotNull();
			library = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Map<String, List<String>> table = new HashMap<>();
		Map<String, List<String>> stubs = new HashMap<>();
		for (LispVal form : LispReader.readAllFromString(library)) {
			if (!(form instanceof LispCons def && def.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && rest.cdr() instanceof LispCons init)) {
				continue;
			}
			if (name.name().endsWith("%CLOJURE-CLASS-BASES-TEXT")) {
				for (String line : ((LispString) init.car()).value().split("\n")) {
					if (!line.isEmpty()) {
						List<String> words = List.of(line.split(" "));
						List<String> bases = new ArrayList<>(words.subList(1, words.size()));
						if (bases.get(0).equals("-")) {
							bases.remove(0);
						}
						table.put(words.get(0), bases);
					}
				}
			}
			else if (name.name().endsWith("%CLOJURE-LANG-CLASSES")) {
				for (LispVal row : ((LispCons) ((LispCons) init.car()).cdr()).car() instanceof LispCons rows
						? rows.toList() : List.<LispVal>of()) {
					List<LispVal> cells = ((LispCons) row).toList();
					List<String> bases = new ArrayList<>();
					if ((((LispInteger) cells.get(1)).value() & 512) == 0) {
						bases.add(((LispString) cells.get(2)).value());
					}
					for (String each : ((LispString) cells.get(3)).value().split(" ")) {
						if (!each.isEmpty()) {
							bases.add(each);
						}
					}
					stubs.put(((LispString) cells.get(0)).value(), bases);
				}
			}
		}
		assertThat(stubs).containsKey("clojure.lang.PersistentVector");
		assertThat(table).containsAllEntriesOf(stubs).containsKeys("java.lang.Long", "java.util.List");
		for (Map.Entry<String, List<String>> line : table.entrySet()) {
			if (!line.getKey().startsWith("clojure.")) {
				assertThat(line.getValue()).as(line.getKey()).isEqualTo(reflected(line.getKey()));
			}
			assertThat(table.keySet()).as(line.getKey()).containsAll(line.getValue());
		}
	}

	private static List<String> reflected(String name) throws ClassNotFoundException {
		Class<?> type = Class.forName(name);
		List<String> bases = new ArrayList<>();
		if (type.getSuperclass() != null) {
			bases.add(type.getSuperclass().getName());
		}
		for (Class<?> each : type.getInterfaces()) {
			bases.add(each.getName());
		}
		return bases;
	}

}
