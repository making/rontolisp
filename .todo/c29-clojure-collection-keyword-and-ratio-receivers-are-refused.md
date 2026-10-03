# c29. Clojure: a collection, keyword or ratio receiver of an instance call is refused

Difficulty: Medium

Measured 2026-10-03, exec jar vs oracle `clj` 1.12.6 (interpreter; the JVM goes through the
same `java:call`). A string, number, character or `t` receiver is called as its Java object
(`.kb/java-interop.md`, "A Lisp value as a `java:call` receiver"); a Clojure collection,
keyword or ratio has no Java object, so every method on it is refused:

| form | oracle | ronto |
|---|---|---|
| `(.count [1 2 3])` | `3` | `java:call expects a java object as the first argument, got #(1 2 3)` |
| `(.size [1 2])` | `2` | same refusal |
| `(.get {:a 1} :a)` | `1` | same refusal (`got #<HASH-TABLE ...>`) |
| `(.contains #{1} 1)` | `true` | same refusal (`got (:C%SET ...)`) |
| `(.getName :abc)` | `abc` | same refusal (`got (:C%KEYWORD "abc")`) |
| `(.getNamespace :a/b)` | `a` | same refusal |
| `(.numerator 1/3)` | `1` | same refusal |

These are `clojure.lang` / `java.util` interface methods (`Counted`, `List`, `Map`, `Set`,
`Named`, `Ratio`), so the receiver rule cannot reach them: the value has no host class.
Map the common ones in the lowering, as `stringMethod` maps `String`'s
(`ClojureInteropLowering`), so they also run on wasm; refuse the rest by name. Pin the cases
in `ClojureInteropTest` against the oracle.
