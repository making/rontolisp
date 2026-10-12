# f58. Clojure: a Java `Comparator` object as `sort`'s comparator; `reify`/`deftype` of `Comparator`

Difficulty: Medium

Found while writing `examples/clojure/java-interop.clj` (2026-10-12, interpreter; the JVM backend
alike). The oracle's `sort`, `sort-by` and the sorted constructors take any
`java.util.Comparator`, and a `reify`/`deftype` may implement it:

| program | clj 1.12.6 | here |
|---|---|---|
| `(sort (java.util.Comparator/naturalOrder) [3 1 2])` | `(1 2 3)` | `not a function` |
| `(sort-by count (java.util.Comparator/reverseOrder) ["a" "ccc" "bb"])` | `("ccc" "bb" "a")` | `not a function` |
| `(sorted-set-by (java.util.Comparator/reverseOrder) 1 3 2)` | `#{3 2 1}` | `a sorted collection needs a comparator function` |
| `(sort (reify java.util.Comparator (compare [_ a b] (compare b a))) [1 3 2])` | `(3 2 1)` | `java.util.Comparator is not supported yet as an interface of reify` (lowering) |
| `(deftype D [] java.util.Comparator (compare [_ a b] (compare b a)))` | `user.D` | the same refusal for `deftype` |

Calling the host comparator as a function stays the oracle's `ClassCastException` (`... cannot be
cast to class clojure.lang.IFn`). A fn already crosses into Java as a `Comparator`, with its
default methods (`f53`), and `(Comparator/comparing count)` answers a host object `.sort` takes:
only the Clojure side reading a host comparator is missing.

## What decides the design

- Where `sort` and the sorted collections call their comparator: one helper that calls a fn
  through `AFunction.compare`'s reading (`.kb/clojure-frontend.md` "Sorted collections") can
  call a host `Comparator`'s `compare` through `java:call` where the host is; wasm has no host
  object to hand it, so nothing changes there.
- `reify`/`deftype` of `Comparator`: the interface groups behind arm families
  (`reify.md`, "Host interfaces"); a `compare` row joins the `Comparable` row's family, and the
  Java face (`f10`, `f22`) makes the value the `Comparator` Java sees.

## Plan

1. `ClojureInteropTest` rows for the table on the interpreter and the JVM; clojure-spec rows for
   the `reify`/`deftype` lines on all four backends.
2. The sort helper over a host comparator; the `Comparator` interface for `reify`/`deftype`.
3. `reify.md`/`sort.md` en + ja; use a `reify` of `Comparator` in
   `examples/clojure/java-interop.clj`.
