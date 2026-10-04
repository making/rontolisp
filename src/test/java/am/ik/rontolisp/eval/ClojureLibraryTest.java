package am.ik.rontolisp.eval;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.Clojure;
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

	@Test
	void aProgramMakingNoStreamSplicesTheLibraryWithoutItsStreamArms() {
		// the printer's and str's stream arms go like the unbound ones: only a program
		// that can hold a stream (a read of *out*, a StringWriter, ...) keeps them
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-STREAM-P X)");
		List<LispVal> plain = Clojure.read("(prn (str 1) (with-out-str (print 2)))", null);
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("STREAM-P");
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-STR-OF")).doesNotContain("%CLOJURE-STREAM-P");
		List<LispVal> stream = Clojure.read("(prn *out*)", null);
		assertThat(defun(ClojureLibrary.process(stream), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-STREAM-P X)");
	}

	@Test
	void classOfAStreamIsAnArmAProgramMakingNoStreamSheds() {
		// the library keeps its own defuns (the tree-shaker prunes them later); the arm
		// is
		// in the program's forms
		List<LispVal> plain = Clojure.read("(prn (class 1) (class [1]))", null);
		assertThat(
				ClojureLibrary.process(plain).stream().map(LispVal::print).filter(text -> !text.startsWith("(DEFUN ")))
			.noneMatch(text -> text.contains("%CLOJURE-STREAM-CLASS"));
		List<LispVal> stream = Clojure.read("(prn (class *out*))", null);
		assertThat(
				ClojureLibrary.process(stream).stream().map(LispVal::print).filter(text -> !text.startsWith("(DEFUN ")))
			.anyMatch(text -> text.contains("(RONTOLISP::%CLOJURE-STREAM-CLASS"));
	}

	@Test
	void aProgramReadingNoStreamDepthShedsTheRebindingPairs() {
		// with-out-str, a binding of *out* and an agent action rebind the counters, which
		// only a #'*out* / #'*in* / #'*agent* site reads: without one the pairs go and
		// the
		// program compiles as before the counters existed
		List<LispVal> plain = Clojure.read("(println (with-out-str (print 1))) (binding [*out* *out*] (println 2))",
				null);
		assertThat(ClojureLibrary.process(plain).stream().map(LispVal::print))
			.noneMatch(text -> text.contains("%CLOJURE-OUT-DEPTH"));
		// a program naming no library function goes through the strip too
		List<LispVal> bare = LispReader.readAllFromString("(defvar rontolisp::%clojure-out-depth 0)"
				+ " (let ((*standard-output* s) (rontolisp::%clojure-out-depth (+ rontolisp::%clojure-out-depth 1)))"
				+ " (princ 1))");
		assertThat(ClojureLibrary.process(bare).stream().map(LispVal::print))
			.containsExactly("(LET ((*STANDARD-OUTPUT* S)) (PRINC 1))");
		List<LispVal> read = Clojure.read("(println (with-out-str (print (thread-bound? #'*out*))))", null);
		assertThat(ClojureLibrary.process(read).stream().map(LispVal::print))
			.anyMatch(text -> text.contains("(RONTOLISP::%CLOJURE-OUT-DEPTH (+ RONTOLISP::%CLOJURE-OUT-DEPTH 1))"))
			.anyMatch(text -> text.equals("(DEFVAR RONTOLISP::%CLOJURE-OUT-DEPTH 0)"));
	}

	@Test
	void aProgramNamingNoPrintFlagSplicesTheLibraryWithoutItsPrintArms() {
		// the printer reads *print-length*, *print-level* and *print-readably* through
		// arms: the interpreter keeps them, a compiled program naming none of the three
		// gets the printer it had before they existed
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE")).contains(
				"(RONTOLISP::%CLOJURE-PRINT-DEEP-P X)", "(RONTOLISP::%CLOJURE-PRINT-CUT-P X)",
				"RONTOLISP::%CLOJURE-WRITE-NESTED");
		List<LispVal> plain = LispReader.readAllFromString("(rontolisp::%clojure-str-of x \"\" nil)");
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("%CLOJURE-PRINT-",
				"%CLOJURE-WRITE-NESTED");
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-PRINT")).doesNotContain("%CLOJURE-PRINT-READABLE");
		List<LispVal> flagged = Clojure.read("(binding [*print-length* 2] (prn [1 2 3]))", null);
		assertThat(defun(ClojureLibrary.process(flagged), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-PRINT-CUT-P X)", "RONTOLISP::%CLOJURE-WRITE-NESTED");
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
