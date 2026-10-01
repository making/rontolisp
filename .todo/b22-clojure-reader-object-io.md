# Clojure: reader-object IO slice for hangman/sequences (shcloj4 basics)

Difficulty: Medium (lowering + one library splice; JVM/interp only, wasm
refusal pinned. No new value model).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

Hangman + `sequences.clj` IO basics that are NOT covered today:

- `(clojure.java.io/reader "words.txt")` -> `unknown namespace:
  clojure.java.io` (only `clojure.string` resolves; `ns`/`require` row).
- `(line-seq rdr)` over a READER object -> today's `line-seq` takes a PATH
  (`(line-seq "/tmp/...")` verified working; reader-object arity missing).
  Corpus: hangman `available-words` (`with-open` + `jio/reader` + `line-seq`
  + `filter` + `vec`), `eager.clj` `non-blank-lines*`, `sequences.clj`
  `clojure-loc` (`with-open` + `reader` + `line-seq`).
- `spit`/`slurp`/`with-open`/`line-seq`-over-path already green (keep as
  regression pins).

Still refused after this item (documented non-goals, b16): `file-seq`
(directory walks need a design), `load-string`/`read-string`/`eval`
(`utils.clj` `format-for-book`), `clojure.xml`/`clojure.set`/
`clojure.test`/`clojure.spec.*`, transducer 3-arity `into`/`transduce`/
`eduction` (`eager.clj` perf chapter), `require :reload-all` with a
computed symbol (`test.clj`).

## Design sketch

- Resolve `clojure.java.io` as a second known namespace with exactly
  `reader` (path -> buffered reader over the existing file-stream runtime;
  `read-load-streams.md` is the invariant doc). No other `jio` fns.
- `line-seq` gains the reader-object arity (strict list of lines, like the
  path form; closes nothing -- `with-open` owns closing, matching the
  oracle and the existing `with-open` lowering).
- `ns`/`require` `:as`/`refer` wiring for the new namespace follows the
  `clojure.string` row (alias, `:refer`, bare `clojure.java.io/fn`).

## Acceptance

- `clojure-spec.yaml`: `jio/reader` + `line-seq`-over-reader +
  `with-open` close, `line-seq`-over-path regression, unknown-`jio`-fn
  refusal -- green on interpreter + JVM; wasm legs pin the filesystem
  refusal.
- Corpus pins: hangman `available-words` filtering shape over a fixture
  file (vendored words sample, not the 32k corpus file), `non-blank-lines`
  count over a fixture.
- `.kb/clojure-frontend.md` `spit`/`slurp`/`line-seq` row + `ns` row
  widened; doc pages en+ja.

## Depends on

b16 (harness). b20's `*in*` is the sibling IO special; otherwise
independent of b17-b19/b21.
