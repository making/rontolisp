package am.ik.rontolisp.eval;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureLibraryTest {

	@Test
	void theLibraryDefinesThePrinterHelpers() {
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-STR-OF")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-WRITE-DATUM")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-CALL")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("PRINC")).isFalse();
	}

	@Test
	void aProgramReferencingAPrinterHelperGetsTheLibrarySpliced() {
		List<LispVal> program = List.of(new am.ik.rontolisp.LispCons(
				new am.ik.rontolisp.LispSymbol("RONTOLISP::%CLOJURE-STR-OF"),
				new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol("X"), am.ik.rontolisp.LispNil.INSTANCE)));
		List<LispVal> processed = ClojureLibrary.process(program);
		assertThat(processed.size()).isGreaterThan(program.size());
		String text = processed.stream().map(LispVal::print).collect(Collectors.joining("\n"));
		assertThat(text).contains("DEFUN RONTOLISP::%CLOJURE-STR-OF");
	}

	@Test
	void aProgramBuildingNoSortedCollectionSplicesTheLibraryWithoutItsSortedArms() {
		// the interpreter's library keeps every arm (what a session reads next is
		// unknown); a compiled program that builds no sorted collection gets the library
		// with its arms folded away, and so its own forms
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-P COLL)");
		List<LispVal> plain = LispReader
			.readAllFromString("(rontolisp::%clojure-str-of (if (rontolisp::%clojure-sorted-p x) 1 x) \"\" nil)");
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-STRICT-SEQ")).doesNotContain("SORTED");
		assertThat(processed.get(processed.size() - 1).print()).isEqualTo("(RONTOLISP::%CLOJURE-STR-OF X \"\" NIL)");
		List<LispVal> sorted = LispReader.readAllFromString(
				"(rontolisp::%clojure-str-of (rontolisp::%clojure-sorted-make t nil (list 1)) \"\" nil)");
		assertThat(defun(ClojureLibrary.process(sorted), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-P COLL)");
	}

	@Test
	void aProgramMakingNoUnboundRootSplicesTheLibraryWithoutItsUnboundArms() {
		// the unbound-root arms (the printer's, IFn's) go like the sorted ones: only a
		// program storing an unbound root (a declare, a value-less def) keeps them
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-UNBOUND-P X)");
		List<LispVal> plain = LispReader.readAllFromString("(rontolisp::%clojure-str-of x \"\" nil)");
		assertThat(defun(ClojureLibrary.process(plain), "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("UNBOUND");
		List<LispVal> unbound = LispReader.readAllFromString(
				"(setq x (rontolisp::%clojure-unbound \"user/x\")) (rontolisp::%clojure-str-of x \"\" nil)");
		assertThat(defun(ClojureLibrary.process(unbound), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-UNBOUND-P X)");
	}

	private static String defun(List<LispVal> forms, String name) {
		return forms.stream()
			.map(LispVal::print)
			.filter(text -> text.startsWith("(DEFUN " + name + " "))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void aProgramWithoutAPrinterHelperIsReturnedUnchanged() {
		List<LispVal> program = List.of(new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol("PRINC"),
				new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispString("hi"), am.ik.rontolisp.LispNil.INSTANCE)));
		assertThat(ClojureLibrary.process(program)).isSameAs(program);
	}

}
