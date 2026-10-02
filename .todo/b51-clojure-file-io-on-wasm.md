# Clojure file IO runs on wasm with a preopen, but the docs say there is no filesystem

Difficulty: Small

`doc/en/clojure/reference/io.md` (and the `ja` mirror) says `spit`/`slurp`/`line-seq`/`clojure.java.io/reader`
"run on the interpreter and the JVM -- there is no filesystem on wasm". That shorthand is wrong the same
way it would be for Common Lisp: the wasm backends have a real filesystem behind WASI, gated on a `--dir`
preopen covering the path (`.kb/read-load-streams.md`; the driver itself passes `--dir . --dir /tmp`).
`.kb/clojure-frontend.md` already records "measured 2026-10-01: with a `--dir` preopen both wasm backends
read like the rest".

There is no technical blocker. Every Clojure file entry point lowers to pure core-CL file primitives
(`src/main/java/am/ik/rontolisp/clojure/ClojureLowering.java`), none of which touches the `java:` surface
that wasm rejects:

- `spit` -> `with-open-file` (`:direction :output`, `:append`/`:supersede`) + `write-string` (`spitForm`)
- `slurp` -> `with-open-file` + a `read-char` loop into a string stream (`slurpForm`)
- `line-seq` -> `with-open-file` + a `read-line` loop for a path, the bare `read-line` loop for an open
  reader (`lineSeqForm`)
- `jio/reader` -> an `open` input stream

and both wasm backends implement each of `open`/`with-open-file`/`read-char`/`read-line`/`write-string`
over the preview1 imports (`path_open`, `fd_read`, `fd_write`, ...). What "no filesystem" actually means is
only the un-preopened case: without a `--dir` covering the path, the open signals the file-error -- which
is exactly what `ClojureWasmFileRefusalTest` pins on both wasm backends, and exactly what Common Lisp does
too.

## The fix

1. Reword `doc/en/clojure/reference/io.md` + `doc/ja/clojure/reference/io.md` (and the matching
   `clojure-frontend.md` row): from "no filesystem on wasm" to "on wasm they need a `--dir` preopen
   covering the path, like `open`/`with-open-file`; without one the open signals the file-error" --
   mirroring how `doc/en/reference/functions/open.md` and `directory.md` phrase it for Common Lisp.
2. Pin the preopened case. The shared spec yaml cannot pin file IO, so this lives in Java-side tests like
   the `ClojureInteropTest` file section: add a `ClojureWasmFileIoTest` (or a with-preopen sibling in
   `ClojureWasmFileRefusalTest`) that runs `spit`/`slurp`/`line-seq`/`jio/reader` programs under wasmtime
   `--dir` on BOTH wasm backends. Note only the read path has been measured so far -- the `spit` write
   path needs the same probe before the docs may claim it.

Gate: the new preopen test green on both wasm backends, the existing refusal test still green without a
preopen, `io.md` en+ja reworded, `DocExamplesTest` green.
