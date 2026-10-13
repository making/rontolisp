# f97. Core values snapshotting lower-time state still box fresh per reference

Difficulty: Medium

Found closing `.todo/f95` (2026-10-13): f95 shares every site-independent core
value program-wide through `%clojure-shared-value`, so two references to `map`
are `=` and `identical?` like the oracle's var roots. Eight names keep a fresh
object per reference site, so they are never `=` to themselves, where the
oracle answers `true`:

- `(= all-ns all-ns)`, `(= the-ns the-ns)`, `(= find-ns find-ns)`,
  `(= ns-name ns-name)`, `(= loaded-libs loaded-libs)`,
  `(= read read)`, `(= read-string read-string)`,
  `(= default-data-readers default-data-readers)` are `false` here, `true` on
  `clj` 1.12.6.

## What decides the design

- Each of these closes over lower-time state that still evolves while the
  program lowers (`ClojureLowering.sharedCoreValue`, `UNSHARED_VALUES`): the
  namespaces known where the reference stands, the libraries loaded so far,
  the namespace and its aliases so far, the resolved reader vars. Sharing
  them first-wins would answer a later call through an early value with the
  early snapshot, where today each site reads its own.
- Sharing them correctly means reading that state at run time instead of
  snapshotting at lower time -- a semantic change to the calls, not just to
  identity -- or proving no program can observe the snapshot move.

## Plan

1. Two references to each named core value are `=` and `identical?`, like the
   oracle (clj 1.12.6), without moving any call's snapshot.
2. clojure-spec rows where the behavior is observable, four backends.
