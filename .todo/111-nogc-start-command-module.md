# 111 — `--no-gc` `_start` command-module mode (top-level forms + direct `wasmtime run`)

The stretch goal deliberately scoped OUT of todo 110 (assessed 2026-07-10: a
sizeable new compilation surface, not a seam question). 110 landed everything it
needs underneath: `print`/`princ`/`terpri` over the conditional fd_write import,
the `__write_stdout` funnel, the `__ftoa` float printer, and `with-arena`.

## Goal

Accept top-level non-defun forms under `--no-gc`, compiled into a `_start`
function, so a console program runs DIRECTLY:

```
rontolisp prog.lisp --no-gc -o prog.wasm
wasmtime run prog.wasm          # no --invoke, no -W gc
```

Today `NoGcWasmCompiler.compile` rejects anything but defuns +
`rontolisp:wasm-export` directives ("--no-gc supports only (defun ...) and
(rontolisp:wasm-export ...) at top level"), and a `--no-gc` module is always a
reactor.

## Work sketch

- Collect top-level non-defun forms into a synthetic zero-arg body compiled like
  a defun (reuse `collectCalls`/`inferTypes`/`compileExpr` verbatim — the forms
  must satisfy the same eligibility subset), exported as `_start` (a command
  module; keep the reactor shape when only directives are present so existing
  output stays byte-identical).
- The todo-88 wrapper auto-reset and the export machinery don't apply to
  `_start` (no boundary values); `print` inside works as of 110.
- No globals exist on this backend, so top-level `setq`/`defvar` stay rejected;
  a top-level `let`-wrapped program is the expected idiom.
- Decide whether `wasm-export` directives may coexist with `_start` (probably
  yes: emit both).

## Unlocked follow-ups (record from 110)

- `examples/examples.yaml`: promote the `no-gc` token from COMPILE-only to a RUN
  token (`wasmtime run prog.wasm` + output check), and add a printing
  `mandelbrot-nogc` variant (today it returns one `:string` because printing
  did not exist; with `_start` + `print` the ordinary `mandelbrot.lisp` style
  works under `--no-gc` and its output can be asserted).
- The `ExamplesE2eTest` harness then verifies `--no-gc` output end-to-end
  (today it only asserts the compile succeeds).

## Constraints inherited from 110/93

- Print-free programs (and all existing reactor-only programs) stay
  byte-identical — `_start` emission must be gated on a top-level form being
  present.
- Keep all function-index math behind the `Mem.funcIndex()`/`*Index()`
  accessors (the fd_write import shifts indices by 1 when printing).

## Related

- `.todo/110-nogc-print-io-and-with-arena.md` (done; the print/arena base),
  `.todo/93-nogc-component-compact-export.md` (component wrap; unaffected —
  a command module is for `wasmtime run`, the component wrap is for reactors),
  `.kb/no-gc-scalar-wasm.md`.
