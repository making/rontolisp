# b75. `set!` of a thread-bound var

Difficulty: Medium

`set! of a var is not supported yet: *warn-on-reflection* ...` -- the corpus
program `instant.clj` stops at its line 13, `(set! *warn-on-reflection* true)`,
the one `set!` the shcloj4 corpus spells outside the `xml_callback.clj` editor
snippet (which `set!`s three `^:dynamic` vars inside a `binding`). b61 lowered
only the deftype mutable field.

## Oracle (Clojure CLI 1.12.6, measured 2026-10-02)

- `(set! *warn-on-reflection* true)` at a script's top level answers `true`:
  `clojure.main` binds that var (and `*unchecked-math*`, `*print-meta*`,
  `*print-length*`, `*print-level*`, `*ns*`, ...) around the load.
- `(def ^:dynamic *d* 1) (binding [*d* 5] (set! *d* 2) *d*)` answers `2`.
- `(set! *d* 2)` outside any `binding`: run-time
  `Can't change/establish root binding of: *d* with set` (the non-dynamic case
  already lowers to that error).

## Design

`binding` lowers to a plain special `let*`, so nothing knows at run time whether
a var is thread-bound. Options: a per-var binding-depth counter maintained by the
`binding` lowering (`set!` tests it and signals at depth 0), or treating the
`clojure.main`-bound compiler flags as always bound (a `set!` of a flag with no
effect here just answers the value). Pin both in `clojure-spec.yaml` on all four
backends; instant.clj then stops at its next gap (`extend-protocol` over the host
class `Instant`).
