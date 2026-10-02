# b85. `read`/`read-string` (concurrency persistence round-trip)

Difficulty: High

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `examples.test.concurrency/test-add-message-with-backup` needs
  `(read (java.io.PushbackReader. (reader "output/messages-backup.clj")))`:
  ronto `error: .../concurrency.clj:33:12: unknown name: read`.
  The `reader` (`clojure.java.io`, lowers to `open`), `PushbackReader.` /
  `StringReader.` / `File.` construction and `spit` all work individually.
- Oracle semantics probed: `reader` -> `java.io.BufferedReader`; `read`
  takes the pushback reader and returns ONE form advancing past it;
  `read-string "(4 5)"` -> `(4 5)`.
- Ronto refusals probed (each `unknown name:`): `read`, `read-string`,
  `load-string`, `eval`, `load`, `load-file`, `type`. `JIO_VARS` is exactly
  `{"reader"}` (`ClojureNamespaceLowering.java:135`). The concurrency test
  needs the `read`-over-stream half plus record printing `read` can
  reconstruct (`chat/->Message` round-tripped through `spit`).
- The other three concurrency deftests (counter, `slow-double`/`memoize`,
  `demo-memoize` timing shape) already pass; only the backup test is blocked.

## Plan

- Phase 1 (this item, medium-hard): `read-string` over strings first: a
  runtime Clojure reader (vectors, maps, keywords, sets, record literals)
  as spliced Lisp over the string primitives every backend already
  compiles; pin in `clojure-spec.yaml` on all four backends.
- Phase 2 (this item, hard): `read` over streams: a stream layer over the
  `open`-file-stream `reader` returns (`read-char`/`unread` integration)
  plus record reconstruction of what `spit` prints.
- `eval`/`load-string` (runtime compiler over `ClojureLowering`) stays out;
  recommend scoping out explicitly in the item if phase 1 lands and phase 2
  does not. `type` (while here, `class` exists at
  `ClojureLowering.java:2733` with no `type` arm) folds in only if free.

## Pin

- `clojure-spec.yaml` (all four backends): `read-string` round-trips incl.
  a defrecord literal; `read` over a `reader` stream advancing one form.
- `ClojureInteropTest` (interpreter + JVM): the hangman
  `available-words`-adjacent file shape if touched.
- E2E: `examples.test.concurrency` byte-identical to the oracle.
- wasm file legs need a `--dir` preopen (`ClojureWasmFileIoTest` pattern).
