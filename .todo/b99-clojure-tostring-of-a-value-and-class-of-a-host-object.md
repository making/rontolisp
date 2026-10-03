# b99. `.toString` of a Clojure value and `class` of a host object

Difficulty: Medium

Measured 2026-10-03, oracle `clj` 1.12.6.1673 vs the b85 worktree (interpreter;
the corpus sweep of b85 found `examples.test.multimethods` at 2 errors, the
same before and after b85):

- `(.toString 42)` oracle `42` / ronto `java:call expects a java object as the
  first argument, got 42`; the same for a keyword (oracle `:k`) and a vector
  (oracle `[1 "a"]`). A string and a stream receiver are mapped
  (`ClojureInteropLowering.stringMethod`/`streamMethod`); any other Lisp value
  reaches `java:call`.
- `(class (java.io.File. "foo"))` oracle `java.io.File` / ronto `class needs a
  value of a known kind`, so the book's `my-print` (`(defmulti my-print class)`
  with a `:default` method) errors for a host object where the oracle
  dispatches to `:default` (`#<foo>`).
- Witness: `examples.test.multimethods/test-my-print`
  (`(my-print-str 42)` and `(my-print-str (java.io.File. "foo"))`).

## Plan

- `.toString` over a value that is no host object: its `str` spelling
  (`%clojure-str-of x "nil" nil`), beside the string/stream arms, on every
  backend.
- `class` of a host object: the host class on the interpreter and the JVM (the
  b83 class-object value), without adding a `java:` reference to programs that
  never use interop (`.kb/java-interop.md`: a `java:` program changes the JVM
  hash-table accessors); dispatch then reaches `:default`. b92 owns how the
  class object prints.

## Pin

- `clojure-spec.yaml`: `.toString` of a number, keyword and vector (all four
  backends); `ClojureInteropTest`: `class` of a host object and the
  `:default` dispatch.
- E2E: `examples.test.multimethods` byte-identical to the oracle.
