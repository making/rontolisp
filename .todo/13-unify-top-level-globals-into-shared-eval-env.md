# Compiler: fully-CL global environment (bidirectional eval <-> compiled sharing)

**Status:** partial -- top-level global bindings are mirrored one-way into the
eval runtime's global environment (commit 94978ff). Full CL semantics (a single
shared, bidirectional global environment) is not implemented.

**Do this together with `.todo/07-compiler-global-special-var-in-function-body.md`.**
07 (a global read/assigned from inside a `defun`/`lambda` body does not compile)
is the same root cause -- top-level globals live in a `main`/`_start` local, not
a shared store. The unified design below solves both in one change: once every
global read/write (top level, function body, and eval) goes through one store,
the function-body case is just an ordinary env read and the one-way mirror is
removed. Doing them together avoids a throwaway intermediate patch, a transient
two-store inconsistency, and a second round of cross-backend verification (the
perf trade-off and benchmark are shared too). Land as one PR; close 07 when this
lands.

## Background

In Common Lisp there is ONE global environment. A special/global variable
defined at top level is visible to `eval`, and `eval` and compiled code see each
other's writes -- it is the same store. rontolisp instead compiles a top-level
`setq`/`defvar`/`defparameter`/`defconstant` global into a `main()`/`_start`
local (fast path) and keeps the embedded `eval` runtime's global environment
separate (JVM `_genv`, WASM `GLOBAL_ENV`).

The current fix (`Jvm/WasmSetqCompiler.mirrorTopLevelGlobal`, gated on
`Ctx.topLevel` + `usesEval`) copies the value into `_genv`/`GLOBAL_ENV` on every
top-level global assignment, so `eval` can READ a global the compiled program
defined (e.g. `(setq add10 (make-adder 10))` then `(eval '(funcall add10 100))`
-> 110). It is write-through and **one-way only**.

## Symptom / divergence from CL

The mirror is compiled -> eval only, so the two stores can drift:

```lisp
(setq counter 0)
(eval '(setq counter 41))   ; updates _genv/GLOBAL_ENV only
(print counter)             ; compiled read sees its own local copy -> 0, not 41
(print (eval 'counter))     ; eval sees 41
```

Real CL prints `41` for both. There is also the related compile-time gap in
`.todo/07-compiler-global-special-var-in-function-body.md`: a global referenced or
`setq`-assigned from inside a `defun`/`lambda` body does not compile at all.

## What to implement (full CL semantics)

Make top-level globals a single shared store that both compiled code and the
runtime `eval` read and write:

1. Treat a top-level `defvar`/`defparameter`/`defconstant`/`setq` global as
   living in `_genv`/`GLOBAL_ENV` (the eval global env), not a `main`/`_start`
   local. (Or keep a dedicated compiled-global store and have `eval` share it --
   pick one store, not two.)
2. Compile a bare global READ (top level AND inside a function/lambda body, which
   is exactly `.todo/07-compiler-global-special-var-in-function-body.md`) to an
   `_env_lookup`/`_genv` read rather than a local-slot load. A new
   `Jvm/WasmGlobalRefCompiler` (or a fallback in
   `Jvm/WasmExprCompiler.compileSymbolRef`).
3. Compile a global `setq`/`setf` (anywhere) to an `_store` into the same env, so
   writes from compiled code and from `eval` are mutually visible.
4. Keep the fast local-slot path only for genuine lexicals (let/lambda/defun
   params), never for globals.
5. Decide the `usesEval` interaction: today `_genv`/`GLOBAL_ENV` and `_store`
   exist only when the program uses eval. A shared global store must exist
   whenever the program has any global variable, independent of eval -- so emit
   the env global + `_store`/`_env_lookup` helpers under a broader
   `usesGlobals || usesEval` gate (watch WASM function-index stability and the
   `--component` blob assumptions).

## Trade-off to weigh first

Every global access becomes an environment lookup (a cons-chain walk or a
`_genv` probe) instead of a local-slot load, so this is slower than the current
mirror for the common define-then-compute case. Options: linear `_genv` alist
(simple, current eval behavior), or a dedicated global-symbol -> slot/index map
resolved at compile time with the runtime env used only as the shared backing
for eval. Benchmark `examples/nn.lisp` / `mlp.lisp` (hot `defparameter` reads)
before committing to a representation.

## Verify

Interpreter / JVM / WASM Preview 1 / WASM component, plus a `ci-spec.yaml` E2E
case covering the bidirectional drift example above. Landing this subsumes both
the current one-way mirror and
`.todo/07-compiler-global-special-var-in-function-body.md` (which can then be closed),
and lets `examples/nqueens.lisp` / `mandelbrot.lisp` read their globals directly
instead of threading them as arguments.
