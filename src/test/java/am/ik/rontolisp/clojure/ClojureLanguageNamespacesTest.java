package am.ik.rontolisp.clojure;

import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
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
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [clojure.pprint :as pp])) (pp/cl-format nil \"~a\" 1)",
				null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("clojure.pprint/cl-format is not built in");
	}

	@Test
	void coreProtocolsLoadsAtStartupAndRefusesItsTwoArityProtocol() {
		assertThat(lowered("(clojure.core.protocols/datafy 1)", Map.of()))
			.contains("(DEFUN |c%clojure.core.protocols/datafy|");
		assertThatThrownBy(() -> Clojure.read("(require '[clojure.core.protocols :as p]) (p/coll-reduce [1] +)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(
					"clojure.core.protocols/coll-reduce is not built in: a protocol method of two arities is not built in");
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
