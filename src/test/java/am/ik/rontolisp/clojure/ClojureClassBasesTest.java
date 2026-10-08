package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
		// beyond the tables, a throwable reflects; any other class is no class keyword
		assertThat(ClojureClassBases.basesOf("java.io.FileNotFoundException")).containsExactly("java.io.IOException");
		assertThat(ClojureClassBases.basesOf("java.util.Date")).isNull();
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
