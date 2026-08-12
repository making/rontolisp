# Exporting an async-defun on Preview 1 answers the raw future

Difficulty: Medium

Found while reproducing the JSPI re-entrancy corruption (todo 337): a
`rontolisp:wasm-export` whose target is a top-level `rontolisp:async-defun`
works under `--component` (the wrapper polls / drives the future --
`WasmExportCompiler.emitBody`'s `asyncTarget` branch, gated on
`ctx.asyncFuncBase >= 0`), but on Preview 1 / `--no-wasi` the degenerate
lowering leaves `asyncDefunNames` EMPTY and `asyncFuncBase = -1`, so the
wrapper unboxes the returned `TYPE_P1_FUTURE` struct as if it were the declared
scalar/string and traps with `illegal cast` on the very first call.

```lisp
(rontolisp:wasm-import 'slow :from "env" :params '(:int) :returns :int :async t)
(rontolisp:async-defun probe (n) (rontolisp:await (slow n)))
(rontolisp:wasm-export 'probe :params '(:int) :returns :int)  ; traps at call time
```

The working spelling -- what the clack reactor transport
(`%http-reactor-handle`) and `examples/cloudflare-workers/dog-fetcher` do -- is
a plain defun that `rontolisp::%future-force`s the future before returning.
That is exactly what the wrapper should do itself: on this backend the future
is settled at creation, so the fix is small -- when the export target is a
lowered async-defun (or, more robustly, whenever the returned value IS a
`TYPE_P1_FUTURE`), force it before the unbox, mirroring the courtesy the
`--component` wrapper and the reactor transport already extend.

Decide between:

1. Track the lowered async-defun names on the P1 path too and force only for
   those targets (byte-identical everywhere else, but misses a plain defun that
   returns someone else's future).
2. A dynamic `ref.test TYPE_P1_FUTURE -> %future-force` in every wrapper whose
   declared return is not `:void` (covers the pass-through case; costs a few
   bytes per export on modules that never suspend, which the byte-identity
   discipline forbids -- so gate it on the module using async at all).

Either way, a compile-time ERROR naming the spelling would already be better
than the runtime `illegal cast`: the wrapper knows its target is an
async-defun.

## Verification

- The reproduction above answers `n + 100` (sync host) / the settled value
  instead of trapping, on `--no-wasi` and plain Preview 1, both optimize
  levels.
- A module with no async surface stays byte-identical.
- `--component` export wrappers unchanged (their `asyncTarget` branch already
  handles this).
