# Clojure: bare `require`/`use` accept quoted libspecs (shcloj4 follow-up)

Difficulty: Low (lowering-only: unwrap the quote; no runtime, no backend
change).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673; transcripts in
`.todo/artefacts/b16-shcloj4-inventory/` probes `rq-quoted`,
`rq-unquoted`, `rq-ns`)

Quoting is backwards between the two implementations:

| Probe | oracle 1.12.6 | rontolisp today |
|---|---|---|
| `(require '[clojure.string :as str])` | `a,b` (works) | `error: require takes library specs, not …` (refused) |
| `(require [clojure.string :as str])` (unquoted) | `ClassNotFoundException` (fails) | `a,b` (works) |
| `(ns foo (:require [clojure.string :as str]))` | works | works (already green) |

Corpus blocks (both spell the quoted form): `life_without_multi.clj:25`
(`(require '[clojure.string :as str])`), `sequences.clj`
(`(require '[clojure.java.io :refer [reader]])` and the
`clojure.string` `:refer [blank? …]` line), `eager.clj`'s
`clojure.java.io` uses.

## Design sketch

- Accept `(quote spec)` / `'spec` items in bare `require` (and `use`,
  same path) alongside bare-vector specs; keep accepting the unquoted
  shape (lenient superset, document the deviation: the oracle rejects it).
- The `ns` clause path already quotes implicitly -- share the spec
  parser rather than adding a second one.

## Acceptance

- `clojure-spec.yaml` (or `ClojureLoweringTest` if stream-based):
  quoted `:as`/`:refer`/bare specs for `clojure.string` (+ refusal for a
  quoted unknown namespace), green on all four backends.
- Corpus pins: `life_without_multi.clj` `my-print-vector` shape,
  `sequences.clj` `non-blank?` shape (past the b22 `jio/reader` gap).
- `.kb/clojure-frontend.md` `ns`/`require` row widened (quoted specs);
  doc pages en+ja if the row has user-facing surface.

## Depends on

b16 (harness). Independent of b17-b23 (b22's corpus pins need both).
