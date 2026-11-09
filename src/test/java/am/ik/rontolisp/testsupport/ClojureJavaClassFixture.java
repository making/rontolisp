package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A Clojure library shipping plain Java classes beside its sources, as specter ships
 * {@code MutableCell} and {@code Util}: where no host is each is read as the Clojure it
 * amounts to ({@code .kb/clojure-frontend.md}, "Portable reading of a dependency's Java
 * class"), so the program prints the oracle's lines on every backend. The classes spell
 * what the reading covers -- a private field with its getter and setter, constructors of
 * two arities chained by {@code this(...)}, a {@code toString}, static constants and a
 * static counter, branches, a loop over an array, a switch, int overflow, a
 * {@code boolean} and a {@code char} at the boundary -- and the library's macro builds a
 * value of one while it expands. {@code Greeting} concatenates a string through
 * {@code invokedynamic}, which the reading does not cover: it stays the host's, refused
 * when the program calls it where no host is -- but a macro body runs in the macro-time
 * image, a host, which answers its member while the program lowers, whatever the target.
 */
public final class ClojureJavaClassFixture {

	/** The library's Java sources. */
	private static final Map<String, String> JAVA = Map.of("fixture/plain/Cell.java", """
			package fixture.plain;
			public class Cell {
				private Object value;
				public Cell(Object value) { this.value = value; }
				public Object get() { return value; }
				public void set(Object value) { this.value = value; }
			}
			""", "fixture/plain/Util.java", """
			package fixture.plain;
			public class Util {
				public static final int WIDTH = 32;
				public static final String NAME = "util";
				private static long calls;
				public static Object[] makeObjectArray(int size) { calls++; return new Object[size]; }
				public static long calls() { return calls; }
				public static int clamp(int x, int lo, int hi) { return x < lo ? lo : x > hi ? hi : x; }
				public static long sum(long[] xs) { long s = 0; for (long x : xs) { s += x; } return s; }
				public static int mix(int a, int b) { return (a * 31 + b) ^ (a >>> 3); }
				public static String kind(int n) {
					switch (n) {
						case 0: return "zero";
						case 1: return "one";
						default: return "many";
					}
				}
				public static boolean even(long n) { return n % 2 == 0; }
				public static char upper(char c) { return c >= 'a' && c <= 'z' ? (char) (c - 32) : c; }
				public static double mean(double[] xs) {
					double s = 0;
					for (double x : xs) { s += x; }
					return xs.length == 0 ? 0 : s / xs.length;
				}
			}
			""", "fixture/plain/Counter.java", """
			package fixture.plain;
			public class Counter {
				private int count;
				private final String label;
				public Counter() { this("counter"); }
				public Counter(String label) { this.label = label; }
				public Counter add(int n) { count += n; return this; }
				public int count() { return count; }
				public boolean isEmpty() { return count == 0; }
				public String label() { return label; }
				@Override public String toString() { return label; }
			}
			""", "fixture/plain/Greeting.java", """
			package fixture.plain;
			public class Greeting {
				private final String name;
				public Greeting(String name) { this.name = name; }
				public String text() { return "hello, " + name; }
			}
			""");

	/** The library's Clojure namespace over its classes. */
	private static final Map<String, String> FILES = Map.of("fixture/plain/lib.clj", """
			(ns fixture.plain.lib
			  (:import [fixture.plain Cell Greeting]))
			(defn cell [v] (Cell. v))
			(defn get-cell [^Cell c] (.get c))
			(defn set-cell! [^Cell c v] (.set c v))
			(defmacro cell-of-sum [& xs] (get-cell (cell (apply + xs))))
			(defn greet [s] (.text (Greeting. s)))
			(defmacro greeting-of [s] (greet s))
			""");

	/** The program over the library. */
	private static final String PROGRAM = """
			(ns main
			  (:require [fixture.plain.lib :as lib])
			  (:import [fixture.plain Cell Util Counter]))
			(def c (lib/cell :a))
			(prn (lib/get-cell c) (lib/set-cell! c [1 2]) (lib/get-cell c) (class c) (instance? Cell c) (instance? Cell :a))
			(prn (try (lib/get-cell :not-a-cell) (catch ClassCastException e :cast)) (lib/cell-of-sum 1 2 3))
			(prn Util/WIDTH Util/NAME (Util/clamp 5 0 3) (Util/clamp -1 0 3) (Util/clamp 2 0 3) (Util/mix 2147483647 7))
			(prn (Util/sum (long-array [1 2 3])) (map #(Util/kind %) [0 1 5]) (Util/even 4) (Util/even 7))
			(prn (Util/upper \\q) (Util/upper \\Q) (Util/mean (double-array [1 2 4])) (Util/mean (double-array [])))
			(prn (count (Util/makeObjectArray 3)) (Util/calls))
			(let [k (Counter.) n (Counter. "n")]
			  (prn (.isEmpty k) (.count (.add (.add k 2) 3)) (.isEmpty k) (.label k) (str n) (.label n)))
			(prn (lib/greeting-of "y"))
			(prn (try (lib/greet "x") (catch Exception e :refused)))
			""";

	/**
	 * What every backend prints of the classes the reading covers (clj 1.12.6's lines).
	 */
	private static final String READ = """
			:a nil [1 2] fixture.plain.Cell true false
			:cast 6
			32 "util" 3 0 2 1879048215
			6 ("zero" "one" "many") true false
			\\Q \\Q 2.3333333333333335 0.0
			3 1
			true 5 false "counter" "n" "n"
			"hello, y"
			""";

	/** What {@link #PROGRAM} prints where the host is: the oracle's lines. */
	public static final String HOST_OUTPUT = READ + "\"hello, x\"\n";

	/**
	 * What {@link #PROGRAM} prints where no host is: the oracle's lines but the class the
	 * reading does not cover, refused when called.
	 */
	public static final String WASM_OUTPUT = READ + ":refused\n";

	private ClojureJavaClassFixture() {
	}

	/**
	 * Writes the project: the library's jar, the {@code deps.edn} naming it and the
	 * program.
	 * @param dir an empty directory
	 * @return the program's file
	 */
	public static Path project(Path dir) {
		try {
			JavaLibraryJar.build(dir, "plain-lib.jar", JAVA, entry -> true, FILES);
			Files.writeString(dir.resolve("deps.edn"), "{:deps {fixture/plain {:local/root \"plain-lib.jar\"}}}\n");
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
		return program.resolveSibling("plain-lib.jar");
	}

}
