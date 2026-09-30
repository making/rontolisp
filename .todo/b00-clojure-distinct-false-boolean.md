# b00: Clojure booleans -- a distinct false apart from nil

Difficulty: Medium

## Premise (measured, 2026-09-30)

`false` folds into `nil` (`.kb/clojure-frontend.md`, "Deviations"). Both are falsey
so tests behave, but the values are indistinguishable. Measured on the interpreter
(Clojure CLI 1.12 as oracle):

- `(= false nil)` answers `T` here, `false` there.
- `(nil? false)` answers `T` here, `false` there.
- `(false? false)` / `(true? true)` / `(boolean? x)` are `unknown name` here.
- `println` of `true`/`false`/`nil` prints `TNILNIL` (concatenated, no separator)
  here, `true false nil` (spaced) there.
- `(str nil)` answers `"NIL"` here (`princ-to-string` of `NIL`), `""` there;
  `(str false)` answers `"NIL"` here, `"false"` there.

Pinned by the `false-folds-into-nil` case of `clojure-spec.yaml` (all four
backends agree on the folded behavior).

## Shape

- The distinct-object treatment `(scheme char)`'s false value uses: a dedicated
  false object, distinct from `NIL`, falsey in every conditional (`if`/`when`/
  `cond`/`and`/`or`), per `.kb/clojure-frontend.md`.
- `false?`/`true?`/`boolean?` lower to their predicates; `nil?` stops answering
  `T` for `false`; `(= false nil)` stops answering `T`.
- Printing: `true`/`false` print as `true`/`false` through `princ` (and through
  `str`), `nil` as `nil` or `""` per the `str`-vs-`println` rule Clojure states.
- The `println` separator question belongs to [[b06]]; this item only makes the
  three values distinct and printable.

## Tests

- `clojure-spec.yaml`: the `false-folds-into-nil` case becomes
  `false-is-distinct-from-nil` with the oracle's answers; `ClojureLoweringTest`
  pins the new lowering; `ClojureSpecE2eTest` runs all four backends.
