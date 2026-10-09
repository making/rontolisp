# e89. Clojure: values handed to Java as the oracle's own objects, not copies

Difficulty: High

e73 hands Java a Clojure value it has none of through `%clojure-host-value`
(`.kb/clojure-frontend.md`, "Java interop"): a keyword or symbol is a `java:handle` that hashes
and orders like the oracle's; a set, map, record, sorted collection or lazy seq a fresh Java
copy. What still differs from `clj` 1.12.6, measured 2026-10-09 on the interpreter and the JVM:

| program | oracle | here |
|---|---|---|
| `(str (java.util.ArrayList. [#{1} {:a [1 2]}]))` | `[#{1}, {:a [1 2]}]` | `[[1], {:a=[1, 2]}]` |
| `(str (java.util.Collections/unmodifiableSet #{1 2}))` | `#{1 2}` | `[1, 2]` |
| `(let [v [3 1 2]] (java.util.Collections/sort v) v)` | `UnsupportedOperationException` | `[3 1 2]` |
| `(java.util.Collections/max [1/2 1/3])` | `1/2` | `No matching method java.util.Collections.max with 1 argument(s)` |
| `(str (java.util.ArrayList. [(atom 1)]))` | `[clojure.lang.Atom@...]` | `No matching constructor for java.util.ArrayList with 1 argument(s)` |
| `(deftype T1 [a]) (str (java.util.ArrayList. [(T1. 1)]))` | `[user.T1@...]` | `No matching constructor ...` |
| `(.sort l (fn [a b] nil))` | `NullPointerException` | `java:reify: cannot return NIL as int from java.util.Comparator.compare` |
| `(java.util.TreeSet. [:a 'b])` | `ClassCastException` | `[:a b]` |

- The oracle passes the persistent collection itself: Java reads it through the `java.util`
  interfaces, its `toString` is Clojure's printer, a write throws
  `UnsupportedOperationException`. A copy prints the Java way and takes writes silently.
- A ratio is a `Number` and a `Comparable` there; an atom, a deftype or a reify an object of
  its own class, equal only to itself.
- A Comparator fn's nil or non-number answer is the oracle's `NullPointerException` /
  `ClassCastException`, which a `catch` takes by class.
- A keyword and a symbol do not compare there.

## Plan

1. A read-only Java view of a Lisp collection as a generic `java:` value, shaped like
   `java:handle` (an interpreter class, a generated `$`-class compiled, the unmarshal arms
   answering the value back), whose `toString` is a printer the caller names. Measure a view
   against the copy on the JVM direct sites first (resolution status, size).
2. A handle that orders by a Lisp comparison (a ratio by value) and one equal only to itself
   (an atom, a deftype, a reify), or a handle kind refusing comparison across kinds.
3. In comparison mode (`JavaImplementation.readsComparison`), throw `NullPointerException` /
   `ClassCastException` for a nil / non-number answer in all three copies (interpreter
   `ImplementationHandler.comparison`, the bridge's `comparison`, `_jcmp`).
4. Pin in `ClojureInteropTest` and the bridge parity test; `doc/{en,ja}/clojure/deviations.md`
   (the fn and the collection bullets).
