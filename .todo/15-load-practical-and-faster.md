# Make `load` practical and fast across the compilers

**Status:** not started. Motivated by `examples/hiragana/` (a multi-file Lisp
program: `common.lisp` + `prototypes.lisp` + `train-main.lisp`, and
`common.lisp` + `weights.lisp` + `infer-main.lisp`). Today those files are
**concatenated** by `gen.sh` before compiling, because `load` does not compose
with the compilers. This TODO is about removing that workaround.

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

### A. Compile-time `load` / include (closes gap 1 — highest value)

Treat a **top-level** `(load "x.lisp")` with a literal path as a *compile-time
include*: splice `x.lisp`'s forms into the compilation unit before Pass 1, so the
backends see the definitions and compile them natively (no `--dynamic`, no perf
loss). Natural home: a pre-compile pass alongside `PackageResolver` (root
`am.ik.rontolisp`), which already runs before the evaluator and both compilers
and rewrites forms. Open questions:
- Only literal-path, top-level `load` qualifies; a runtime/computed `load` stays
  dynamic (document the split, like the `open` `:direction` literal rule).
- Cycle/idempotency guard (a `require`-style "load once" set).
- Path resolution relative to the including file vs. CWD.
- Interpreter parity: the interpreter already evaluates `load` at runtime, so a
  compile-time include must not double-define there — either share the include
  expansion, or keep interpreter on its runtime path.
This would let `examples/hiragana/gen.sh` drop the `cat` steps and instead have
`train.lisp`/`infer.lisp` be thin files that `(load ...)` the shared pieces.

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

- A multi-file program using top-level `(load ...)` compiles and runs natively on
  interpreter / JVM / WASM Preview1 / WASM component with identical output (no
  `--dynamic`, no concatenation) — direction A.
- (Optional) `examples/hiragana/` rewritten to `load` the shared `.lisp` pieces
  instead of `cat` in `gen.sh`, with the committed `infer.wasm` unchanged in
  behavior.
- README "Compiled `eval` limitations" / load sections and CLAUDE.md updated to
  describe the compile-time-include semantics and the browser virtual-FS option.
