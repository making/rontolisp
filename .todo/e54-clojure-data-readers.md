# e54. Clojure: `data_readers.clj` tagged literals

Difficulty: High

A library's `data_readers.clj`/`data_readers.cljc` (at a source root or jar root) maps a tag to
a reader function var (`{time/date time-literals.read-write/date}`); the oracle merges every
one on the classpath into `*data-readers*` at startup, and its reader calls the var when a form
is READ. Here none is read: such a tag is the reader's `No reader function for tag t` and
`*data-readers*` is `{}` (`.kb/clojure-frontend.md`, "deps.edn", gap 4 of `e39`).

## Why it is not a small change

- A whole file is read before its first form lowers (`Clojure.read`, `loadFile`), so a reader
  function's namespace -- loaded by a `require` earlier in the same file in the oracle -- could
  never run in time. Honoring a tag means reading form by form, interleaved with lowering, as
  the oracle's `load` does.
- The reader function runs at read time: the macro-time evaluator (`eval/ClojureMacroTime`,
  which now runs the program's definitions, `e46`) is where it would run; its answer must be
  a datum the lowering accepts (a value with no source spelling -- a `java.time` object -- has
  no representation on the wasm backends).
- `*data-readers*` at run time and `read-string`'s use of it move with it. `#inst`/`#uuid` are
  built into both readers (`.kb/clojure-frontend.md`, "Instants and UUIDs"): a table consulted
  first leaves them the defaults, and `default-data-readers` is still to define.

## Plan

1. Measure on `clj` 1.12.6: a tag in the same file as the `require` of its namespace, in a
   required file, a missing var, `.cljc` readers, two roots defining one tag.
2. Decide the read interleaving (`ClojureLowering.lower`, `loadFile`, the session).
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja` deviations.
