package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The class chains a catch tests: resolved from host reflection, the {@code clojure.lang}
 * throwables from the oracle's table, and the chains {@code clojure.lisp} spells by hand
 * for the classes the oracle throws where the runtime signals a runtime error.
 */
class ClojureThrowablesTest {

	@Test
	void aChainIsTheClassThenEverySuperclassUpToThrowable() {
		assertThat(ClojureThrowables.chainOf("java.lang.NumberFormatException")).containsExactly(
				"java.lang.NumberFormatException", "java.lang.IllegalArgumentException", "java.lang.RuntimeException",
				"java.lang.Exception", "java.lang.Throwable");
		assertThat(ClojureThrowables.chainOf("java.lang.AssertionError")).containsExactly("java.lang.AssertionError",
				"java.lang.Error", "java.lang.Throwable");
		assertThat(ClojureThrowables.chainOf("java.lang.Throwable")).containsExactly("java.lang.Throwable");
		// clojure.lang is not on this class path: the oracle's table (clj 1.12.6)
		assertThat(ClojureThrowables.chainOf("clojure.lang.ArityException")).containsExactly(
				"clojure.lang.ArityException", "java.lang.IllegalArgumentException", "java.lang.RuntimeException",
				"java.lang.Exception", "java.lang.Throwable");
		assertThat(ClojureThrowables.chainOf("clojure.lang.ExceptionInfo")).containsExactly(
				"clojure.lang.ExceptionInfo", "java.lang.RuntimeException", "java.lang.Exception",
				"java.lang.Throwable");
		// beyond the table, reflection
		assertThat(ClojureThrowables.chainOf("java.io.FileNotFoundException")).containsExactly(
				"java.io.FileNotFoundException", "java.io.IOException", "java.lang.Exception", "java.lang.Throwable");
		// no throwable, no class
		assertThat(ClojureThrowables.chainOf("java.lang.String")).isNull();
		assertThat(ClojureThrowables.chainOf("no.such.Thing")).isNull();
	}

	@Test
	void theTableIsEveryDefaultImportedThrowableWithItsHostSuperclass() throws ClassNotFoundException {
		// the table answers on hosts that cannot reflect these classes, so on this one
		// it must say what reflection says, and hold every throwable a program names
		// without an import
		for (Map.Entry<String, String> row : ClojureThrowables.PARENTS.entrySet()) {
			if (row.getKey().startsWith("java.")) {
				assertThat(Class.forName(row.getKey()).getSuperclass().getName()).as(row.getKey())
					.isEqualTo(row.getValue());
			}
		}
		for (String simple : ClojureNamespaceLowering.JAVA_LANG) {
			Class<?> type = Class.forName("java.lang." + simple);
			if (Throwable.class.isAssignableFrom(type) && type != Throwable.class) {
				assertThat(ClojureThrowables.PARENTS).as(simple).containsKey(type.getName());
			}
		}
	}

	@Test
	void theChainALibraryRuntimeErrorTakesIsTheClassesOwn() {
		// the runtime-error side of a catch spells its chains by hand
		// (%clojure-error-chain): each must be the real class's chain, or a catch of a
		// superclass the hand-written list misses would let the error pass
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		Map<String, String> signals = new LinkedHashMap<>();
		signals.put("(/ 1 0)", "java.lang.ArithmeticException");
		signals.put("(+ 1 \"a\")", "java.lang.ClassCastException");
		signals.put("(+ 1 nil)", "java.lang.NullPointerException");
		signals.put("(aref (vector 1 2) 5)", "java.lang.IndexOutOfBoundsException");
		signals.put("(length 5)", "java.lang.UnsupportedOperationException");
		signals.put("(funcall (lambda (a) a) 1 2)", "clojure.lang.ArityException");
		signals.put("(open \"/nonexistent/dir/x\")", "java.io.FileNotFoundException");
		for (Map.Entry<String, String> signal : signals.entrySet()) {
			LispVal chain = evaluator.eval(LispReader
				.readAllFromString(
						"(rontolisp::%clojure-error-chain (handler-case " + signal.getKey() + " (error (c) c)))")
				.get(0));
			List<String> names = new ArrayList<>();
			for (LispVal rest = chain; rest instanceof LispCons cons; rest = cons.cdr()) {
				names.add(((LispString) cons.car()).value());
			}
			assertThat(names).as(signal.getKey()).isEqualTo(ClojureThrowables.chainOf(signal.getValue()));
		}
		// a condition whose type tells no class answers none
		assertThat(evaluator.eval(LispReader
			.readAllFromString("(rontolisp::%clojure-error-chain (handler-case (error \"x\") (error (c) c)))")
			.get(0))).isEqualTo(LispNil.INSTANCE);
	}

}
