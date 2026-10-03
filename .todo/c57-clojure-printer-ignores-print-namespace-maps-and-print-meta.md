# c57. Clojure printer ignores `*print-namespace-maps*` and `*print-meta*`; `*assert*` does not switch `assert` off

Difficulty: Medium

The flags read their oracle root (2026-10-03) but the printer reads only `*print-length*`,
`*print-level*` and `*print-readably*`. The oracle (`clj` 1.12.6, `clj -M file`, where
`clojure.main` binds `*print-namespace-maps*` true):

- `(prn {:a/b 1 :a/c 2})` prints `#:a{:b 1, :c 2}` (ours `{:a/b 1, :a/c 2}`); bound false,
  `{:a/b 1}`. This is the DEFAULT output of every map whose keys share one namespace.
- `(binding [*print-meta* true] (prn (with-meta [1] {:a 1})))` prints `^{:a 1} [1]`.
- `(set! *assert* false)` at the top level makes a LATER `(assert false)` pass (the flag
  is read when `assert` expands); a `binding` around an already-expanded `assert` does not.

Plan: namespace-map printing as a printer arm when every key is a keyword or symbol of
one namespace (gated like the `PRINT_FLAGS` family when the flag is false); `*print-meta*`
reading `%clojure-meta-table`; `assert` honouring a lower-time `set!` of `*assert*` to a
literal. Pin in clojure-spec against the oracle.
