# Make `load` practical and fast across the compilers

**Status:** direction A DONE (compile-time include); directions B and C still
open. Motivated by `examples/hiragana/` (a multi-file Lisp program:
`common.lisp` + `prototypes.lisp` + `train-main.lisp`, and `common.lisp` +
`weights.lisp` + `infer-main.lisp`). Today those files are **concatenated** by
`gen.sh` before compiling, because `load` did not compose with the compilers;
direction A removes the need for that workaround.

## The problem (measured)

`load` works only in the interpreter. The two compilers cannot use it:

```lisp
;; helper.lisp:  (defun add3 (x) (+ x 3))
;; main.lisp:    (load "helper.lisp") (format t "~a~%" (add3 10))
```

| Backend          | Result                                            |
| ---------------- | ------------------------------------------------- |
| interpreter      | `13` (real `Files`, everything runs at runtime)   |
| JVM compile      | `Cannot compile: add3`                            |
| WASM compile     | `Cannot compile: add3`                            |
| JVM `--dynamic`  | compiles, prints `13`, but `add3` runs INTERPRETED via embedded `eval` (slow) |

Two distinct gaps:

1. **Static visibility.** The compilers resolve function/variable names at
   compile time (Pass 1 collects defuns from the source). A definition that only
   exists after a runtime `load` is invisible, so direct calls fail to compile.
   `--dynamic` papers over it by routing the call through the embedded `eval`
   (`Jvm/WasmDynamicCallCompiler`), i.e. tree-walked, not compiled.

2. **No filesystem at runtime in the browser.** `load`/`open`/`with-open-file`
   call `path_open`, and the browser `wasi-shim.js` returns `WASI_ENOENT`
   (`examples/hiragana/wasi-shim.js`). So even `--dynamic` `load` cannot read a
   file in the browser. This is exactly why the hiragana inference module bakes
   its weights in (decision "B1") instead of loading them ("B2").

## Directions (pick per use case)

### A. Compile-time `load` / include (closes gap 1 — highest value) — DONE

Implemented as `am.ik.rontolisp.cli.LoadInliner`, wired into
`RontoLispCli.compileToFile` (compile path only). A **top-level**
`(load "x.lisp")` with a string-literal path is treated as a *compile-time
include*: `x.lisp`'s forms are spliced into the program before the compilers run
(and before their `PackageResolver` pass), so Pass 1 sees the definitions and
compiles them natively — no `--dynamic`, no perf loss. Tests: `LoadInlinerTest`
(in-memory loader + a JVM compile-and-run regression).

How the open questions were resolved:
- **Scope:** only a literal-path, top-level `(load "...")` is inlined; a
  runtime/computed `load`, or one nested inside another form, is left untouched
  (still runs at runtime via the embedded reader, e.g. under `--dynamic`).
- **Cycle guard:** a path stack detects circular loads and throws
  (`IllegalStateException: Circular load detected: ...`). NOT idempotent — each
  `load` includes again, matching CL `load` semantics (no `require`-style
  load-once set yet; add one if a diamond include becomes a problem).
- **Path resolution:** CWD-relative, the same as the runtime `load`
  (`SourceLoader.fileSystem()`). File-relative resolution is a follow-up and
  should be applied to both paths together.
- **Interpreter parity:** the inliner runs ONLY on the compile path
  (`compileToFile`); the interpreter keeps its runtime `load`, so no
  double-definition.

Not yet done: rewrite `examples/hiragana/gen.sh` to drop the `cat` steps and have
`train.lisp`/`infer.lisp` `(load ...)` the shared pieces (the mechanism now
supports it; the example just hasn't been migrated).

### B. Browser virtual filesystem (closes gap 2 — enables "B2")

Implement `path_open` (+ sequential `fd_read`/`fd_close`, and `fd_write` for
output files) in `wasi-shim.js` over an in-memory `Map<path, bytes>` supplied by
the page — mirroring how the shim already feeds stdin. Then a fixed
`infer.wasm` could `(load "weights.lisp")` at runtime and the page could swap the
model file without recompiling. Constraint to document: runtime-loaded data goes
through the WASM runtime reader (i31 ints, decimal-only floats), so weights must
be plain in-range decimals (fine for small weights; lose the host-reader
precision that baking gives). Keep it opt-in so non-file demos stay minimal.
See also the `path_open` note in `examples/hiragana/README.md`.

### C. Faster `--dynamic` / runtime-`load`ed code (closes the perf half of gap 1)

When code must stay dynamic (genuinely runtime-determined `load`), the loaded
defuns currently execute in the tree-walking `_eval`. Options: compile loaded
forms on the fly into fresh functions in the runtime function namespace
(`_fenv` / `GLOBAL_FENV`), or cache/specialize hot `_eval` paths. Lower priority
than A/B.

## Acceptance

- [x] A multi-file program using top-level `(load ...)` compiles and runs
  natively on interpreter / JVM / WASM Preview1 / WASM component with identical
  output (no `--dynamic`, no concatenation) — direction A.
- [ ] (Optional) `examples/hiragana/` rewritten to `load` the shared `.lisp`
  pieces instead of `cat` in `gen.sh`, with the committed `infer.wasm` unchanged
  in behavior.
- [x] CLAUDE.md updated to describe the compile-time-include semantics
  (`LoadInliner`). README user-facing `load`/compile sections and the browser
  virtual-FS option (direction B) still to do.
