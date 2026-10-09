package am.ik.rontolisp.clojure;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StrictMathFunction;
import am.ik.rontolisp.eval.ClojureMacroTime;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the clojure.jar namespaces the front end ships as Clojure source
 * ({@link ClojureBuiltinNamespaces}) come from: a namespace the oracle loads before the
 * program is reached by a qualified name alone and is never a project file, any other
 * shipped one follows the source path like a dependency, and a {@code clojure.*}
 * namespace clojure.jar does not define is an ordinary library. What each namespace
 * answers is pinned against the oracle on all four backends by {@code clojure-spec.yaml}.
 */
class ClojureLanguageNamespacesTest {

	@Test
	void aStartupNamespaceLoadsOnItsFirstQualifiedName() {
		String out = lowered("(defn f [x] (clojure.walk/postwalk identity x)) (f [1])", Map.of());
		assertThat(out).contains("(DEFUN |c%clojure.walk/postwalk|").contains("(DEFUN |c%clojure.walk/walk|");
	}

	@Test
	void aProjectFileNeverShadowsAStartupNamespace() {
		Map<String, String> files = Map.of("src/clojure/walk.clj", "(ns clojure.walk) (defn walk [i o f] :mine)");
		assertThat(lowered("(ns a (:require [clojure.walk :as w])) (w/walk inc identity [1])", files))
			.doesNotContain("mine")
			.contains("(DEFUN |c%clojure.walk/walk|");
	}

	@Test
	void aContribClojureNamespaceIsFoundOnTheSourcePath() {
		Map<String, String> files = Map.of("src/clojure/data/json.clj",
				"(ns clojure.data.json) (defn write-str [x] (str \"json:\" x))");
		assertThat(lowered("(ns a (:require [clojure.data.json :as json])) (json/write-str 1)", files))
			.contains("(DEFUN |c%clojure.data.json/write-str|");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [clojure.data.csv :as csv]))", null, null,
				new MemoryClojureFiles(Map.of())))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate clojure/data/csv.clj or clojure/data/csv.cljc on the source path");
	}

	@Test
	void clojureEdnIsALoweringReachedWithoutARequire() {
		assertThat(lowered("(clojure.edn/read-string \"1\")", Map.of()))
			.contains("(RONTOLISP::%CLOJURE-EDN-READ-STRING-1 \"1\")");
		assertThat(
				lowered("(ns a (:require [clojure.edn :as e])) (map e/read-string [\"1\"]) (e/read {} *in*)", Map.of()))
			.contains("#'RONTOLISP::%CLOJURE-EDN-READ-STRING-V")
			.contains("(RONTOLISP::%CLOJURE-EDN-READ ");
		assertThatThrownBy(() -> Clojure.read("(clojure.edn/read-string {} \"x\" \"y\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.edn/read-string");
		assertThatThrownBy(() -> Clojure.read("(clojure.edn/read-str \"x\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.edn/read-str");
	}

	@Test
	void clojurePprintLaysOutThroughItsKernelNamespace() {
		String pprint = Clojure
			.read("(ns a (:require [clojure.pprint :as pp])) (pp/pprint [1])", null, ClojureMacroTime.create())
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(pprint).contains("(DEFUN |c%clojure.pprint/pprint|").contains("(RONTOLISP::%CLOJURE-PP-CALL ");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [rontolisp.internal.pprint :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.pprint is internal to clojure.pprint");
	}

	@Test
	void clFormatIsClojureSourceOverTheNumberAndCaseKernels() {
		String program = Clojure
			.read("(ns a (:require [clojure.pprint :as pp])) (pp/cl-format nil \"~,2F ~:R ~:(~a~)\" 1.5 2 \"x\")"
					+ " ((pp/formatter \"~a\") nil 1) ((pp/formatter-out \"~a\") 1)", null, ClojureMacroTime.create())
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(program).contains("(DEFUN |c%clojure.pprint/cl-format|")
			.contains("(RONTOLISP::%CLOJURE-PP-FIXED ")
			.contains("(RONTOLISP::%CLOJURE-PP-ENGLISH ")
			.contains("(RONTOLISP::%CLOJURE-PP-CASE-CONVERT ")
			.contains("|c%clojure.pprint/formatter-fn|")
			.contains("|c%clojure.pprint/formatter-out-fn|");
	}

	@Test
	void coreProtocolsLoadsAtStartupAndRefusesItsIteratorReduction() {
		assertThat(lowered("(clojure.core.protocols/datafy 1)", Map.of()))
			.contains("(DEFUN |c%clojure.core.protocols/datafy|");
		assertThat(lowered("(require '[clojure.core.protocols :as p]) (p/coll-reduce [1] + 0)", Map.of()))
			.contains("(DEFUN |c%clojure.core.protocols/coll-reduce|");
		assertThatThrownBy(
				() -> Clojure.read("(require '[clojure.core.protocols :as p]) (p/iterator-reduce! nil +)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(
					"clojure.core.protocols/iterator-reduce! is not built in: it reduces a java.util.Iterator");
	}

	@Test
	void clojureCoreReducersLoadsAtItsRequireAndRefusesTheForkJoinPool() {
		String out = lowered("(ns a (:require [clojure.core.reducers :as r])) (r/fold + (r/map inc [1 2]))", Map.of());
		assertThat(out).contains("(DEFUN |c%clojure.core.reducers/fold|")
			.contains("(DEFUN |c%clojure.core.reducers/coll-fold|")
			.contains("(RONTOLISP::%CLOJURE-COLL-REDUCER-ROW ");
		// clj -M has not loaded clojure.core.reducers: a qualified name alone does not
		// reach it.
		assertThat(lowered("(defn f [x] (clojure.edn/read-string x))", Map.of())).doesNotContain("reducers");
		for (String var : new String[] { "pool", "fjtask", "->Cat" }) {
			assertThatThrownBy(
					() -> Clojure.read("(ns a (:require [clojure.core.reducers :as r])) (r/" + var + ")", null))
				.as(var)
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining("clojure.core.reducers/" + var + " is not built in: ");
		}
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [rontolisp.internal.reducers :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.reducers is internal to clojure.core.reducers");
	}

	@Test
	void clojureMathLowersEachDoubleFunctionToOneStrictMathCallAtItsRequire() {
		String out = lowered("(ns a (:require [clojure.math :as m])) (m/sin 1.0) (m/scalb 1.0 2) (m/floor-div 7 2)",
				Map.of());
		assertThat(out).contains("(DEFUN |c%clojure.math/sin|")
			.contains("(%STRICT-MATH :SIN (RONTOLISP::%CLOJURE-DOUBLE ")
			.contains("(%STRICT-MATH :SCALB (RONTOLISP::%CLOJURE-DOUBLE |c%d|) (RONTOLISP::%CLOJURE-INT-CAST ")
			.contains("(RONTOLISP::%CLOJURE-MATH-FLOOR-DIV ");
		// clj -M has not loaded clojure.math: a qualified name alone does not reach it.
		assertThat(lowered("(defn f [x] (clojure.edn/read-string x))", Map.of())).doesNotContain("clojure.math");
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [rontolisp.internal.math :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.math is internal to clojure.math");
	}

	@Test
	void everyStrictMathFunctionIsAClojureMathKernelOfItsArity() {
		// The lowering spells %strict-math's keywords itself (it may not import the
		// compiler package): each kernel names a StrictMath function, and every
		// function has its clojure.math var.
		Map<String, Integer> expected = new HashMap<>();
		for (StrictMathFunction fn : StrictMathFunction.values()) {
			expected.put(fn.keyword(), fn.shape().arity());
		}
		Map<String, Integer> kernels = new HashMap<>();
		ClojureKernelLowering.STRICT_MATH
			.forEach((name, arity) -> kernels.put(":" + name.toUpperCase(Locale.ROOT), arity));
		assertThat(kernels).isEqualTo(expected);
	}

	@Test
	void aLanguageNamespaceNotBuiltInIsRefused() {
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [clojure.inspector :as i]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.inspector");
	}

	private static String lowered(String source, Map<String, String> files) {
		return Clojure.read(source, null, null, new MemoryClojureFiles(files))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

}
