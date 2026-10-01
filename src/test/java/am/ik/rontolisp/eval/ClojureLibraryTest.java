package am.ik.rontolisp.eval;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
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
	void aProgramWithoutAPrinterHelperIsReturnedUnchanged() {
		List<LispVal> program = List.of(new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol("PRINC"),
				new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispString("hi"), am.ik.rontolisp.LispNil.INSTANCE)));
		assertThat(ClojureLibrary.process(program)).isSameAs(program);
	}

}
