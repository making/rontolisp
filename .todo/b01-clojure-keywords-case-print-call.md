# b01: Clojure keywords -- case, printing and call

Difficulty: Medium

## Premise (measured, 2026-09-30)

Keywords are the symbols `:foo` verbatim and the printer upcases them, so `:a`
and `:A` collide and printed keywords drop the colon (`.kb/clojure-frontend.md`,
"Deviations"). Measured on the interpreter (Clojure CLI 1.12 as oracle):

- `(println :a :A :foo)` prints `AAFOO` here, `:a :A :foo` there (upcased AND
  colon-stripped by `princ`, concatenated without separator on top).
- `(= :a :A)` answers `T` here, `false` there.
- `(:a {:a 1})` -- a keyword in call position, the idiomatic map lookup -- is
  refused (`a keyword cannot be called`) here, answers `1` there.
- Namespaced keywords (`:a/b`, `::foo`) mangle to symbols that collide with the
  upcasing and print wrong; `(str :a)` answers `"A"` here, `":a"` there.

Pinned by the `keywords-are-upcased-and-collide-case-insensitively` case of
`clojure-spec.yaml` (all four backends agree on the current behavior).

## Shape

- A case-preserving keyword representation (or a lowering that keeps the
  spelling), so `:a` and `:A` stop colliding.
- Printing with the leading colon in lowercase (`:a`), through `princ` and
  `str`, on every backend (the printer arms are per-backend).
- Keyword-as-function: `(:k m)` lowers to a map lookup (needs [[b02]]'s map
  runtime, or a refusal that names the missing piece instead of the current
  blanket refusal).
- `::` auto-resolve and `:a/b` namespaces decided (implement or refuse by name).

## Tests

- `clojure-spec.yaml`: the keywords case becomes the oracle's answers;
  `ClojureSpecE2eTest` runs all four backends.
