package am.ik.rontolisp.clojure;

import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
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
