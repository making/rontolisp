# m85. Clojure `rontolisp.wit/export` takes `:reactor`

Difficulty: Low

A Clojure world's exports on a command component (`--component`) run without the top level when
a host calls them directly (`wasmtime run --invoke`, a composed component): an export calling
`Integer/parseInt` traps there, while the program's `run` answers (`.kb/clojure-frontend.md`,
"Host boundary"). A Lisp world says `:reactor t` (`.kb/wasm-export-no-wasi.md`,
"`wit-export :reactor t`"); `rontolisp.wit/export` takes `:world` alone.

- Take `{:reactor true}` and pass `:reactor t` to the directive (`ClojureWitLowering.exportCall`,
  `flushWorlds`); refuse it where the CL directive refuses it.
- Measure first what of the Clojure runtime an export reads from the top level (`parseInt` is one;
  find the global), and whether a reactor should be the Clojure default.
- Pins: `ClojureWitBoundaryTest.aWorldIsImplementedAsAComponent`; `doc/{en,ja}/clojure/reference/wit.md`.
