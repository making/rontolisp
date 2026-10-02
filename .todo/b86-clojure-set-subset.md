# b86. `clojure.set` subset (sequences chapter)

Difficulty: Medium

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `examples.test.sequences`: oracle `Ran 6 tests containing 14 assertions.
  0 failures, 0 errors.` / ronto
  `error: .../sequences.clj:9:1: unknown namespace: clojure.set`
  (`ClojureNamespaceLowering.java:397-399`; only `clojure.string`,
  `clojure.java.io`, `clojure.test` are known).
- Oracle reference values probed: `union` -> `#{java chai c}`,
  `difference` -> `#{c}`, `intersection` -> `#{java}`,
  `select #(= 1 (.length %))` -> `#{d c}`, `join` (natural + `{:country
  :nation}` keymap) -> 3 merged maps.
- The same file also needs `clojure.xml` (`parse` + `xml-seq`,
  `demo-xml-seq`) and `file-seq` (`clojure-loc`): both stay non-goals (no XML
  runtime on any backend, esp. wasm; `file-seq` is the existing
  `ClojureLowering.java:2753` refusal). `examples.utils` (`?.` macro,
  `files-on-classpath-at`) blocks the load chain behind them.

## Plan

- Lower `clojure.set` as strict set builders over the shared `equal`
  hash-table runtime (b02, all four backends share it): `union` /
  `difference` / `intersection` / `select` plus `join` (natural + keymap) as
  a labels walk. Covers `test-sets` / `test-joins`.
- Wire the namespace in `isKnownNamespace` beside `clojure.string`, with the
  same `:as`/`:refer`/`(:use ...)` handling; unknown `clojure.*` stays
  refused.
- `clojure.xml`, `file-seq`, class-`proxy`-only deftests, and the `utils`
  classpath half stay out (documented refusals, not this item).

## Pin

- `clojure-spec.yaml` (all four backends): set-union/difference/
  intersection/select/join cases incl. the keymap join.
- E2E: `test-sets` / `test-joins` in isolation oracle-parity (full
  `examples.test.sequences` stays red on the xml/file-seq legs; track that
  explicitly).
