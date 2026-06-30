# Make `load` practical and fast across the compilers

**Status:** directions A and D DONE (compile-time include + file-relative path
resolution); the `examples/hiragana/` migration off `cat` is DONE; directions B
and C still open. Motivated by `examples/hiragana/`, which `gen.sh` used to
**concatenate** (`common.lisp` + `prototypes.lisp` + the trainer into a single
`train.lisp`; `common.lisp` + `weights.lisp` + the inferer into a single
`infer.lisp`) before compiling, because `load` did not compose with the
compilers. Directions A (visibility) and D (run-from-anywhere paths) removed that
workaround: the entry files now `(load ...)` their pieces and `gen.sh` compiles
them directly. With the concatenation gone, the entry sources were renamed from
`train-main.lisp`/`infer-main.lisp` back to `train.lisp`/`infer.lisp` (the
`-main` suffix only existed to avoid colliding with the generated names).

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
- **Path resolution:** file-relative — see direction D below (a relative `load`
  resolves against the loading file's directory, the entry file for the top
  level), applied to BOTH the compile-time include and the runtime `load`.
- **Interpreter parity:** the inliner runs ONLY on the compile path
  (`compileToFile`); the interpreter keeps its runtime `load`, so no
  double-definition.

DONE: `examples/hiragana/gen.sh` no longer concatenates. `train.lisp`
`(load ...)`s `common.lisp` + `prototypes.lisp`; `infer.lisp` `(load ...)`s
`common.lisp` + `weights.lisp`; `gen.sh` compiles those entry files directly. The
old generated concatenation files (which used the `train.lisp`/`infer.lisp`
names) were removed, and the entry sources were renamed into those freed names
(from `train-main.lisp`/`infer-main.lisp`). Verified the load-built `infer.wasm`
is **byte-identical** to the old concatenation build with the same JAR, and
`pred 2 u` matches on all four backends.

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

### D. File-relative `load` path resolution — DONE

A relative `load` path now resolves against the directory of the file doing the
load (like CL's `*load-pathname*`), falling back to the working directory only
for the top-level entry / REPL. Applied consistently to BOTH paths so the
compile-time include and the runtime `load` resolve the same way:

- `SourceLoader.resolve(baseDir, path)` does the (purely lexical) join, and
  `SourceLoader.parentDir(path)` derives the next base dir. A `null`/empty
  `baseDir` returns the path unchanged and skips all `java.nio` path math, so the
  no-filesystem browser loader is untouched (it passes `baseDir == null`).
- Interpreter: `LispEvaluator` keeps a `loadDirStack`; `RontoLispCli` seeds it
  with the entry file's directory via `setLoadBaseDir`, and each runtime `load`
  pushes the loaded file's directory so a nested `load` chains relative to it.
- Compile-time include: `LoadInliner.inline(program, loader, baseDir)` threads
  the entry directory and recurses with each loaded file's directory.

This lets the `examples/` sets run from any working directory (`java -jar JAR
examples/hiragana/infer.lisp` from the repo root resolves its `common.lisp`
/ `weights.lisp`); JVM compile still wants to run inside the dir only because the
class is named after the `-o` file. Tests: `LoadInlinerTest`
(`resolvesRelativePathsAgainstTheLoadingFile`) +
`LispEvaluatorTest#loadResolvesRelativePathsAgainstTheLoadingFile`.

## Acceptance

- [x] A multi-file program using top-level `(load ...)` compiles and runs
  natively on interpreter / JVM / WASM Preview1 / WASM component with identical
  output (no `--dynamic`, no concatenation) — direction A.
- [x] `examples/hiragana/` rewritten to `load` the shared `.lisp` pieces instead
  of `cat` in `gen.sh`, with the committed `infer.wasm` behavior unchanged
  (load-built `.wasm` is byte-identical to the concatenation build; `pred 2 u`
  matches on all four backends).
- [x] Relative `load` paths resolve relative to the loading file (direction D),
  so the `examples/` sets run from any working directory.
- [x] CLAUDE.md updated to describe the compile-time-include semantics
  (`LoadInliner`) and file-relative resolution. README user-facing `load`/compile
  sections and the browser virtual-FS option (direction B) still to do.
