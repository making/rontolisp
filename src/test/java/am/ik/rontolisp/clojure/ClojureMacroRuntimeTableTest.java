package am.ik.rontolisp.clojure;

import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A whole program's macro expanders live in the one function only the run-time expansion
 * calls, so the compile path drops them, with every function a template names, from a
 * program expanding nothing at run time.
 */
class ClojureMacroRuntimeTableTest {

	private static final String HELPER = """
			(ns mac3 (:require [clojure.pprint :as pp]))
			(defn- helper-never-called [x] (pp/cl-format nil "~a" x))
			""";

	private static final String MACRO = "(defmacro m [x] `(helper-never-called ~x))\n";

	@Test
	void aProgramExpandingNothingAtRunTimeKeepsNoExpander() {
		assertThat(compiled(HELPER + MACRO + "(prn 1)")).doesNotContain("C%MACRO-EXPANDER")
			.doesNotContain("C%MACROEXPAND")
			.doesNotContain("(%UNSPELLED-QUOTE |c%mac3/helper-never-called|)");
		assertThat(compiled(HELPER + MACRO + "(prn (macroexpand-1 '(m 1)))")).contains("(DEFUN C%MACRO-EXPANDER ")
			.contains("(%UNSPELLED-QUOTE |c%mac3/helper-never-called|)");
	}

	@Test
	void aMacroWhoseTemplateNamesAnUncalledHelperKeepsNeitherOnWasm() {
		// the template's qualified symbol spells the helper's defun name, which arms the
		// dispatch gate: before the expanders moved behind the run-time expansion the
		// module kept the helper and the whole format executor (2026-10-09: 452,928 ->
		// 890,138 B; now 373,353 -> 373,364 B)
		int without = wasm(HELPER + "(prn 1)").length;
		int with = wasm(HELPER + MACRO + "(prn 1)").length;
		assertThat(with - without).isLessThan(1_000);
	}

	@Test
	void aRunTimeExpansionKeepsNoFunctionATemplateNames() {
		// a template's var symbols are data, never a function designator by name, so the
		// expanders a run-time expansion keeps arm no defun they spell (2026-10-09: both
		// 894 KB while a template spelled them; now 416,039 -> 416,071 B)
		String expanding = "(prn (macroexpand-1 '(m 1)))";
		int plain = wasm(HELPER + "(defmacro m [x] `(inc ~x))\n" + expanding).length;
		int naming = wasm(HELPER + MACRO + expanding).length;
		assertThat(naming - plain).isLessThan(1_000);
	}

	@Test
	void requiringPprintKeepsNoFormatExecutorForARunTimeExpansion() {
		// formatter and formatter-out name their executors in a plain template; the
		// expanders a run-time expansion keeps hold neither (2026-10-09: 373,358 ->
		// 893,999 B while a template spelled them; now 373,358 -> 415,713 B, the
		// run-time expansion and the kept expanders)
		String macro = "(defmacro m [x] `(inc ~x)) ";
		int without = wasm("(ns x (:require [clojure.pprint])) " + macro + "(prn 1)").length;
		int with = wasm("(ns x (:require [clojure.pprint])) " + macro + "(prn (macroexpand-1 '(m 1)))").length;
		assertThat(with - without).isLessThan(100_000);
	}

	@Test
	void walkingDataLoadsNoRunTimeExpansion() {
		// macroexpand-all is a part of clojure.walk, loaded where a program names it
		String walking = "(defmacro m [x] `(inc ~x)) (prn (clojure.walk/postwalk identity [1]))";
		assertThat(compiled(walking)).doesNotContain("C%MACRO-EXPANDER")
			.doesNotContain("|c%clojure.walk/macroexpand-all|");
		assertThat(compiled("(defmacro m [x] `(inc ~x)) (prn (clojure.walk/macroexpand-all '(m 1)))"))
			.contains("(DEFUN C%MACRO-EXPANDER ")
			.contains("|c%clojure.walk/macroexpand-all|");
	}

	private static String compiled(String source) {
		return CompileFrontendAccess.clojure(source, true, false)
			.forms()
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	private static byte[] wasm(String source) {
		CompileFrontendAccess.Program program = CompileFrontendAccess.clojure(source, true, false);
		return WasmLispCompiler.builder().runtimeFeatures(program.features().names()).build().compile(program.forms());
	}

}
