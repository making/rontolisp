# Compiler: fully-CL global environment (bidirectional eval <-> compiled sharing)

**Status:** partial. Two of the three original gaps are closed: (1) top-level
global bindings are mirrored one-way into the eval runtime's global environment
(commit 94978ff); (2) a global read/assigned from inside a `defun`/`lambda` body
now compiles -- each top-level global has a dedicated backing store (JVM static
field / WASM module-level global), collected by `compiler.GlobalVarCollector`, so
a function-body reference is an ordinary `getstatic`/`global.get`.

The **remaining** gap is full CL semantics: a single shared, **bidirectional**
global environment, so an `eval` write to a global is visible to compiled reads
(and vice versa). Today compiled code reads/writes its dedicated backing store
while `eval` reads/writes `_genv`/`GLOBAL_ENV`; the mirror is compiled->eval
only, so the two can still drift (see the example below).

NOTE: the dedicated-store path was chosen deliberately over routing every global
access through `_genv`/`GLOBAL_ENV`, to avoid the per-access alist-walk cost on
hot `defparameter` reads (`examples/ml/nn.lisp` / `examples/ml/mlp.lisp`).
Closing the bidirectional gap therefore means either making the dedicated store
the single shared store that `eval` also uses, or write-through in both
directions -- not simply "route everything through the eval env". The
"What to implement" steps below are written against the older all-in-`_genv`
framing; re-read them in that light.

## Background

In Common Lisp there is ONE global environment. A special/global variable
defined at top level is visible to `eval`, and `eval` and compiled code see each
other's writes -- it is the same store. rontolisp instead gives each top-level
`setq`/`defvar`/`defparameter`/`defconstant` global its own dedicated backing
store (JVM static field / WASM module-level global) and keeps the embedded
`eval` runtime's global environment separate (JVM `_genv`, WASM `GLOBAL_ENV`).

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
(print counter)             ; compiled read sees its own backing store -> 0, not 41
(print (eval 'counter))     ; eval sees 41
```

Real CL prints `41` for both.

## What to implement (full CL semantics)

Make top-level globals a single shared store that both compiled code and the
runtime `eval` read and write:

1. Treat a top-level `defvar`/`defparameter`/`defconstant`/`setq` global as
   living in one store shared with `_genv`/`GLOBAL_ENV` -- pick one store, not
   two (either the eval env backs the dedicated store, or the dedicated store
   backs the eval env).
2. Compile a bare global READ (top level AND inside a function/lambda body) to a
   read of that shared store. A new `Jvm/WasmGlobalRefCompiler` (or a fallback in
   `Jvm/WasmExprCompiler.compileSymbolRef`).
3. Compile a global `setq`/`setf` (anywhere) to a write into the same store, so
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

If the shared store is the eval env, every global access becomes an environment
lookup (a cons-chain walk or a `_genv` probe) instead of a static-field /
module-global load, so this is slower than the current mirror for the common
define-then-compute case. Options: linear `_genv` alist (simple, current eval
behavior), or keep the compile-time-resolved dedicated slot and use the runtime
env only as the shared backing for eval. Benchmark `examples/ml/nn.lisp` /
`examples/ml/mlp.lisp` (hot `defparameter` reads) before committing to a
representation.

## Verify

Interpreter / JVM / WASM Preview 1 / WASM component, plus a
`src/test/resources/ci-spec.yaml` E2E case covering the bidirectional drift
example above. Landing this subsumes the current one-way mirror, and lets
`examples/console/nqueens.lisp` / `examples/console/mandelbrot.lisp` read their
globals directly instead of threading them as arguments.
