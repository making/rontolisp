# e73. Clojure: host false back, sets/keywords out, boolean Comparators across `java:`

Difficulty: High

What the `java:` boundary still does differently from `clj` 1.12.6 after the
`false`/map/fn-receiver work (`.kb/clojure-frontend.md`, "Java interop"; `.kb/java-interop.md`,
"Java's false and hash tables"). Measured 2026-10-08 on the interpreter and the JVM:

| program | oracle | here |
|---|---|---|
| `(let [l (java.util.ArrayList.)] (.add l false) (vec l))` | `[false]` | `[nil]` |
| `(.get (doto (java.util.HashMap.) (.put "x" false)) "x")` | `false` | `nil` |
| `(str (java.util.HashSet. #{1 2}))` | `[1, 2]` | `No matching constructor` |
| `(str (java.util.HashMap. {:a 1}))` | `{:a=1}` | `No matching constructor` |
| `(let [l (java.util.ArrayList. [3 1 2])] (.sort l <) (vec l))` | `[1 2 3]` | `java:reify: cannot return T as int from java.util.Comparator.compare` |

- Host false comes back as nil: the shared unmarshal maps Java false to CL's only false.
  The lowering's `booleanAnswer` covers a primitive-boolean member of a known receiver
  class only; a `Boolean.FALSE` in a collection, an `Object`-typed answer, or a callback
  argument is nil.
- A set `(:C%SET table)`, a keyword `(:C%KEYWORD "a")` and a record are Clojure shapes
  `java:` must not learn (`.kb/clojure-frontend.md`, invariant), so they reach no parameter.
- A fn passed where a `Comparator` is expected answers through the shared return marshal, so
  a boolean answer is refused; the oracle's `AFunction.compare` reads true as -1 and false as
  the reversed call (`(.compare f a b)` on the fn itself already does, `%clojure-fn-compare`).

## Plan

1. One per-site convention the Clojure lowering already marks (`:functional`, or a second
   marker beside it): Java false answered as `|false|` (the call's result, array elements,
   and a callback's arguments at an implementation made there), on all three copies
   (interpreter `unmarshal`, the bridge's, `_junm`/`emitUnmarshal`/`_jarr`) with the static
   result types (`JavaStaticType.ofDeclared` boolean -> {T, FALSE}). Then retire the
   lowering's `booleanAnswer` wraps the marker subsumes.
2. Sets and keywords: a Clojure-side conversion at the marked sites cannot see static types
   (it would turn resolved sites into dispatched ones), so prefer a generic `java:` hook for a
   value of no kind -- measure both on the JVM direct sites first.
3. Comparator: decide whether a marked site's functional implementation of
   `java.util.Comparator.compare` may read a boolean answer as `AFunction.compare` does.
4. Pin in `ClojureInteropTest`, the bridge parity test; `doc/{en,ja}/clojure/deviations.md`
   (the host-boolean and map bullets).
