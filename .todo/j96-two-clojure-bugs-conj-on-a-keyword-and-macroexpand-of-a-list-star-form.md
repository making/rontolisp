# j96. Two Clojure bugs: `conj` on a keyword, `macroexpand-1` of a `list*` form

Difficulty: Low

Seen while working j92, neither reproduced nor fixed there.

- `(conj :kw x)` returns a bogus value instead of throwing `ClassCastException` as `clj`
  1.12 does.
- `macroexpand-1` of a macro call form built with `list*` over a lazy seq (e.g.
  `(macroexpand-1 (list* 'when (lazy-seq ['true 1])))`) returns the form unexpanded; `clj`
  expands it.

Confirm each against `clj` first, then pin it in `src/test/resources/clojure-spec.yaml`
(all four backends) before fixing.
