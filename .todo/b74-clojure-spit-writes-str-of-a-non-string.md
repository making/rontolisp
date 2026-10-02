# b74. Clojure `spit` writes `(str x)` of a non-string

Difficulty: Low

The oracle's `spit` writes `(str content)`: `(spit f '(1 2))` writes `(1 2)`, `(spit f nil)`
writes nothing. Ours lowers `spit` to `write-string` of the content
(`ClojureStringLowering.spitForm`), so any non-string signals `WRITE-STRING expects a
string`. Found in b56 running the shcloj4 `src/examples/concurrency.clj`
(`add-message-with-backup` spits the message list from an agent): the oracle writes the
backup file, ours stops there.

## Acceptance

- `spit` of a list, a vector, a map, a number, a record and nil writes the `str` spelling,
  `:append` included, pinned on the interpreter and the JVM (`ClojureInteropTest`) and on
  wasm with a preopen (`ClojureWasmFileIoTest`); a string is written as before.
- The `spit` row of `.kb/clojure-frontend.md` and `doc/*/clojure/reference/spit.md` say so.
