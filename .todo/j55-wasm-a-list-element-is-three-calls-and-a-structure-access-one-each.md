# j55. wasm: what a list element costs -- a general vector read is three calls, a structure's predicate and accessor one each

Difficulty: High

`.kb/starlark-frontend.md`, "Against CPython (2026-11-16)": on wasm a Starlark program of
list reads and writes stays behind CPython after the front end's own costs were taken out
(`listidx.star` 4.9 s against 1.2 s), and the rest is not the front end's. Measured there
on plain Common Lisp (wasmtime 49, preview 1, ns per iteration of a counting loop):

| | ns |
|---|---|
| the loop and a `+` alone | 9.4 |
| `(aref v i)` of a general vector, adjustable or simple | 28 |
| `(setf (aref v i) x)` | 52 |
| a `dolist` step | 6.5 |
| a `defstruct` predicate and one accessor | 15 |
| the read behind both, bounds tested (what `xs[i]` is) | 60 |

A read is `_idx_ref` (or `_idx_chk`), then `_arr_get`; the store's bound has an inline
arm at the speed levels (`WasmArrayCompiler.emitFlatCheck`), the read's does not. A
predicate or accessor of a structure is a call of its `defun`. A comparison of two fixnums
outside a fused tree is a call too (`_rat_cmp_bits`), and a true answer one more
(`_t_sym`).

## Do

- Measure first which of the three (the read's calls, the structure calls, the boxed
  comparison) a list-indexing loop pays most for: hand-edit the `.wat` of
  `.todo/artefacts/j54-starlark-faster-than-cpython/listidx.star`'s module, or the
  emitters behind a debug switch.
- An inline arm for the rank-1 read of a general vector at the speed levels, with the
  existing calls behind it for everything else (a displaced array, a wide subscript, a
  bound miss): the error shapes are pinned (`.kb/error-handling.md`), so the slow arm
  stays what reports.
- A `defstruct` predicate or accessor that nothing redefined, as the `%obj-is` /
  `%obj-ref` it is, at its call site (`.kb/defstruct.md`).
- Re-run `time.sh` over the eleven programs and record the table in
  `.kb/starlark-frontend.md`; the goal there is each of them under CPython on wasm.
