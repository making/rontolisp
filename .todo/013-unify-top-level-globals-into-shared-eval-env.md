# Compiler: fully-CL global environment (bidirectional eval <-> compiled sharing)

**Status:** partial. Two of the three gaps are now closed:
(1) top-level global bindings are mirrored one-way into the eval runtime's global
environment (commit 94978ff); (2) a global read/assigned from inside a
`defun`/`lambda` body now compiles -- each top-level global has a dedicated
backing store (JVM static field / WASM module-level global), see the now-closed
`.todo/07`. The **remaining** gap is full CL semantics: a single shared,
**bidirectional** global environment, so an `eval` write to a global is visible to
compiled reads (and vice versa). Today compiled code reads/writes its dedicated
backing store while `eval` reads/writes `_genv`/`GLOBAL_ENV`; the mirror is
compiled->eval only, so the two can still drift (see the example below).

NOTE: the chosen fix for `.todo/07` deliberately took the fast dedicated-store
path rather than routing every global access through `_genv`/`GLOBAL_ENV`, to
avoid the per-access alist-walk perf cost on hot `defparameter` reads
(`examples/nn.lisp`/`mlp.lisp`). Closing the bidirectional gap therefore means
either making the dedicated store the single shared store that `eval` also uses,
or write-through in both directions -- not simply "route everything through the
eval env".

The former `.todo/07` (a global read/assigned from inside a `defun`/`lambda`
body did not compile) is DONE and removed: each top-level global now has a
dedicated backing store (JVM static field / WASM module-level global), collected
by `compiler.GlobalVarCollector`, so a function-body reference is an ordinary
`getstatic`/`global.get`. What remains here is making that store (or the eval
env) the SINGLE shared, bidirectional store so `eval` writes and compiled writes
are mutually visible. The "What to implement" steps below are written against the
old all-in-`_genv` framing; re-read them in light of the dedicated-store fix
already in place (the realistic path now is write-through in both directions, or
making the dedicated store the backing the eval env also reads).

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
