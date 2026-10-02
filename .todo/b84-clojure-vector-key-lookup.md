# b84. Structural lookup for vector-keyed sets/maps

Difficulty: Medium

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `(= [1 1] [1 1])` -> `true` on both (b55 structural `%clojure-equal`).
  But `(contains? (set [[1 1] [2 1]]) [1 1])` oracle `true` / ronto `false`;
  `(get {[1 1] :a} [1 1])` oracle `:a` / ronto `nil`.
- Corpus witnesses: `examples.functional/machine` calls a map with vector
  keys (`(step [state (first stream)])` over
  `{[:init 'c] :more ...}`); `examples.test.snake/test-lose`
  (`(contains? (set body) head)` over segment vectors) fails 1/16 assertions
  with the body correct (`{:body ([1 1] [2 1] [1 1]) ...}`) but the lookup
  missing.
- Root cause: sets/maps build `(make-hash-table :test 'equal)`
  (`ClojureCollectionLowering.java:147-151`) but the runtime's `equal` on a
  vector is identity (`.kb/hash-tables.md`), and lookups use bare `gethash`
  (`containsForm :425-456`, `getBranches :369-396`). b55 fixed `=` but left
  lookup identity-based. `conj` onto a set duplicates vector members the
  same way.

- Map members too (measured 2026-10-02 after b80): `(= #{{:a 1}} #{{:a 1}})`
  and `(= #{[1]} #{[1]})` answer `false` (oracle `true`), so set `=` over
  collection members belongs here as well. Witness:
  `examples.test.introduction/test-accounts`
  (`(= #{{:id "CLSS" :balance 0}} @accounts)`), its only remaining failure.

## Plan

- Miss-path structural scan: a `%clojure-gethash`-style helper in
  `clojure.lisp` beside `%clojure-equal` (try `gethash` with a sentinel,
  else `maphash` + `%clojure-equal` scan), wired into the set/map arms of
  `containsForm` and `getBranches` (plus `conj`/`disj`/`assoc` dedup so all
  three agree; the as-value variants share these forms).
- Pure Lisp, portable to all four backends; the scalar-key fast path stays
  untouched.

## Pin

- `clojure-spec.yaml` (all four backends): `contains?`/`get` over
  vector-keyed sets/maps, `conj` dedup of a present vector member.
- E2E: `examples.test.snake` byte-identical to the oracle; `machine` shape
  (`(machine '(c a d r))` -> `true`) oracle-parity case.
