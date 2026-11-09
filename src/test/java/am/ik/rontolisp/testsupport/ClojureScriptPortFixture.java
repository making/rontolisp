package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A Clojure library whose {@code .clj} wraps a Java class compiled against a type its jar
 * omits, as persistent-sorted-set's wraps classes over {@code clojure.lang}, and whose
 * jar ships the namespace's ClojureScript port beside it: the namespace is read from the
 * port ({@code .kb/clojure-frontend.md}, "Classes compiled against `clojure.lang`"). The
 * port spells the ClojureScript surface such a library's port does --
 * {@code :require-macros}, {@code js/Math} and the bare {@code Math}, a deftype over the
 * {@code cljs.core} protocols with a {@code ^:mutable} field its {@code caching-hash}
 * sets and an {@code IPrintWithWriter} printing it, {@code .slice} and {@code js/Error.}
 * -- and its {@code .clj} has a var the port lacks.
 */
public final class ClojureScriptPortFixture {

	/** The library's Java sources; the jar omits {@code Missing}. */
	private static final Map<String, String> JAVA = Map.of("fixture/port/Box.java", """
			package fixture.port;
			public class Box extends Missing {
				public Box() {
				}
			}
			""", "fixture/port/Missing.java", """
			package fixture.port;
			public class Missing {
			}
			""");

	/** The library's Clojure and ClojureScript files. */
	private static final Map<String, String> FILES = Map.of("fixture/port/bag.clj", """
			(ns fixture.port.bag
			  (:import [fixture.port Box]))
			(defn box [] (Box.))
			(defn bag [& xs] (vec xs))
			(defn only-in-clj [] :clj)
			""", "fixture/port/bag.cljs",
			"""
					(ns fixture.port.bag
					  (:require-macros [fixture.port.macros :as m]))
					(def ^:const width (js/Math.pow 2 3))
					(defn half [x] (Math/floor (/ x 2)))
					(deftype Bag [items meta ^:mutable _hash]
					  Object
					  (toString [this] (pr-str* this))
					  IWithMeta
					  (-with-meta [_ m] (Bag. items m _hash))
					  IMeta
					  (-meta [_] meta)
					  ISeqable
					  (-seq [_] (seq items))
					  ICounted
					  (-count [_] (count items))
					  ILookup
					  (-lookup [this k] (-lookup this k nil))
					  (-lookup [_ k not-found] (if (some #(= k %) items) k not-found))
					  ICollection
					  (-conj [this x] (if (contains? this x) this (Bag. (conj items x) meta nil)))
					  IEmptyableCollection
					  (-empty [_] (Bag. [] meta nil))
					  ISet
					  (-disjoin [_ x] (Bag. (vec (remove #(= x %) items)) meta nil))
					  IEquiv
					  (-equiv [this other] (and (set? other) (== (count this) (count other)) (every? #(contains? this %) other)))
					  IHash
					  (-hash [this] (caching-hash this hash-unordered-coll _hash))
					  IFn
					  (-invoke [this k] (-lookup this k))
					  IPrintWithWriter
					  (-pr-writer [this writer opts] (pr-sequential-writer writer pr-writer "#bag[" " " "]" opts (seq this))))
					(defn bag [& xs] (Bag. (vec (distinct xs)) nil nil))
					(defn window [xs from to] (vec (.slice (to-array xs) from to)))
					(defn fail [] (throw (js/Error. "no box here")))
					(defn twice [x] (m/twice x))
					""",
			"fixture/port/macros.clj", """
					(ns fixture.port.macros)
					(defmacro twice [x] `(* 2 ~x))
					""");

	/** The program over the library. */
	private static final String PROGRAM = """
			(ns main (:require [fixture.port.bag :as b]))
			(def x (b/bag 3 1 2))
			(prn b/width (b/half 9) (b/twice 21))
			(prn x (str x) (count x) (seq x) (x 2) (x 9) (get x 1) (contains? x 3) (contains? x 7))
			(prn (conj x 4) (disj x 1) (empty x) (= x #{1 2 3}) (= #{1 2 3} x) (= (hash x) (hash #{1 2 3})))
			(prn (meta (with-meta x {:k 1})) (b/window [1 2 3 4] 1 -1))
			(prn (try (b/fail) (catch RuntimeException e (ex-message e))))
			(prn (try (b/only-in-clj) (catch Exception e (ex-message e))))
			""";

	/**
	 * What {@link #PROGRAM} prints: ClojureScript's answers -- a whole JavaScript number
	 * an integer, the port's own printing, a set equal to a core set either way round --
	 * and the refusal of the var only the {@code .clj} has.
	 */
	public static final String OUTPUT = """
			8 4 42
			#bag[3 1 2] "#bag[3 1 2]" 3 (3 1 2) 2 nil 1 true false
			#bag[3 1 2 4] #bag[3 2] #bag[] true true true
			{:k 1} [2 3]
			"no box here"
			"fixture.port.bag/only-in-clj is not defined by fixture/port/bag.cljs, the ClojureScript port read for \
			fixture.port.bag: fixture/port/bag.clj imports fixture.port.Box, which cannot load here without \
			fixture.port.Missing"
			""";

	private ClojureScriptPortFixture() {
	}

	/**
	 * Writes the project: the library's jar, the {@code deps.edn} naming it and the
	 * program.
	 * @param dir an empty directory
	 * @return the program's file
	 */
	public static Path project(Path dir) {
		try {
			JavaLibraryJar.build(dir, "port-lib.jar", JAVA, entry -> !entry.startsWith("fixture/port/Missing"), FILES);
			Files.writeString(dir.resolve("deps.edn"), "{:deps {fixture/port {:local/root \"port-lib.jar\"}}}\n");
			Path main = dir.resolve("main.clj");
			Files.writeString(main, PROGRAM);
			return main;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The library's jar, the program's Java class path.
	 * @param program the program's file {@link #project} wrote
	 * @return the jar
	 */
	public static Path jar(Path program) {
		return program.resolveSibling("port-lib.jar");
	}

}
