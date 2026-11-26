package am.ik.rontolisp.clojure;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal carriers ({@link ClojureRefusals}) against the library that defines them:
 * each signals the chain {@link ClojureThrowables} resolves for its class, and every
 * function of the library that signals a refusal is one the strip knows.
 */
class ClojureRefusalsTest {

	private static final String REFUSE = "RONTOLISP::%CLOJURE-REFUSE";

	private static Map<String, LispCons> defuns() {
		Map<String, LispCons> defuns = new HashMap<>();
		for (LispVal form : ClojureLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFUN")
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				defuns.put(name.name(), cons);
			}
		}
		return defuns;
	}

	/** The {@code (%clojure-refuse ...)} calls under the form. */
	private static List<LispCons> refuseCalls(LispVal form) {
		List<LispCons> calls = new ArrayList<>();
		collect(form, calls);
		return calls;
	}

	private static void collect(LispVal form, List<LispCons> calls) {
		if (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && head.name().equals(REFUSE)) {
				calls.add(cons);
			}
			for (LispVal run = cons; run instanceof LispCons cell; run = cell.cdr()) {
				collect(cell.car(), calls);
			}
		}
	}

	@Test
	void everyCarrierSignalsTheChainOfItsClass() {
		Map<String, LispCons> defuns = defuns();
		ClojureRefusals.CLASSES.forEach((carrier, className) -> {
			LispCons defun = defuns.get(carrier);
			assertThat(defun).as(carrier).isNotNull();
			List<LispCons> calls = refuseCalls(defun);
			assertThat(calls).as(carrier).hasSize(1);
			// (%clojure-refuse 'CHAIN message)
			LispVal quoted = ((LispCons) calls.get(0).cdr()).car();
			List<String> chain = new ArrayList<>();
			for (LispVal name : ((LispCons) ((LispCons) ((LispCons) quoted).cdr()).car()).toList()) {
				chain.add(((LispString) name).value());
			}
			assertThat(chain).as(carrier).isEqualTo(ClojureThrowables.chainOf(className));
			assertThat(ClojureArms.isFamilyName(carrier)).as(carrier).isTrue();
		});
	}

	@Test
	void aValueCarrierRefusesNilAsTheNullPointerException() {
		// the oracle's cast of nil succeeds and the method call on it throws
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
		// the exception reader a catching program defines
		// (ClojureThrowables.catchRuntime)
		evaluator.eval(LispReader.readAllFromString("(defun c%e-parts (c) (declare (ignore c)) nil)").get(0));
		for (String carrier : List.of("class-cast-exception-of", "illegal-argument-exception-of")) {
			String chains = "(list (handler-case (rontolisp::%clojure-" + carrier + " \"m\" nil)"
					+ " (error (c) (car (rontolisp::%clojure-exact-chain c))))" + " (handler-case (rontolisp::%clojure-"
					+ carrier + " \"m\" 5)" + " (error (c) (car (rontolisp::%clojure-exact-chain c)))))";
			assertThat(evaluator.eval(LispReader.readAllFromString(chains).get(0)).print()).as(carrier)
				.startsWith("(\"java.lang.NullPointerException\" \"java.lang.");
		}
	}

	@Test
	void everyLibraryFunctionSignallingARefusalIsACarrier() {
		// a carrier the strip did not know would splice the refusal runtime into every
		// program reaching it, and its condition into what such a program prints
		Map<String, LispCons> defuns = defuns();
		defuns.forEach((name, defun) -> {
			if (!name.equals(REFUSE) && !refuseCalls(defun).isEmpty()) {
				assertThat(ClojureRefusals.CARRIERS).as(name).contains(name);
			}
		});
		assertThat(defuns.keySet()).containsAll(ClojureRefusals.CARRIERS);
	}

}
