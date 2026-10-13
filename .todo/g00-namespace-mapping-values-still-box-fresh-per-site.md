# g00. Namespace-mapping values still box fresh per reference site

Difficulty: Trivial

Found closing `.todo/f97` (2026-10-13): f97 shares every snapshotting core
value per snapshot through `%clojure-shared-value`, so two references to
`the-ns`, `all-ns`, `read`, ... under one snapshot are `=` and `identical?`
like the oracle's var roots. Six more core values keep a fresh object per
reference site, so they are never `=` to themselves, where the oracle
(clj 1.12.6) answers `true`:

- `(= ns-publics ns-publics)`, `(= ns-interns ns-interns)`,
  `(= ns-map ns-map)`, `(= ns-refers ns-refers)`,
  `(= ns-aliases ns-aliases)`, `(= ns-imports ns-imports)` are `false`
  here, `true` on the oracle.

## What decides the design

- Each value is a one-argument lambda over the `ns-computed-here` worker,
  which refuses every call (`ClojureCoreLowering.valueOf`, the `ns-publics`
  arm): only what the lowering knows maps, so a value can never answer. The
  lambda closes over nothing but its parameter (a fresh temp per site, bound
  and used within), so every site's form is behaviorally equal and one
  program-wide cell is unobservable except for the identity itself -- unlike
  f97's snapshotting names, no per-snapshot key is needed.
- Sharing them is `sharedValue(name, value)` for the six arms (or one shared
  refusal worker), plus clojure-spec rows on four backends and the
  en+ja mirror.

## Plan

1. Two references to each of the six mapping values are `=` and
   `identical?`, like the oracle (clj 1.12.6).
2. clojure-spec rows where the behavior is observable, four backends.
