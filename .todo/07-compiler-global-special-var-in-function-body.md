# Compiler: read/assign a global special variable from inside a function body

**Status:** not implemented in the JVM/WASM compilers (works in the interpreter).

**Do this together with `.todo/13-unify-top-level-globals-into-shared-eval-env.md`.**
Both are symptoms of the same root cause -- top-level globals are compiled to a
`main`/`_start` local instead of a shared global store. The unified
single-global-store design in TODO 13 subsumes this one (a function-body global
reference simply becomes the same env read as everywhere else). Implementing 07
standalone would add a throwaway "fall back to `_genv` for function-body refs"
patch that TODO 13 then rips out, and would leave reads/writes split across two
stores depending on context. Land them as one change; close this TODO when 13
lands. See the "What to implement" steps and the perf trade-off in TODO 13.

## Symptom

A `defparameter`/`defvar` global referenced (or `setq`-assigned) at top level
compiles fine, but the same reference inside a `defun` body fails to compile:

```lisp
(defparameter *k* 3)
(defun f (x) (* x *k*))   ; interpreter: OK
(print (f 5))
```

```
JVM:  java.lang.UnsupportedOperationException: Cannot compile symbol reference: *k*
        at codegen.jvm.JvmExprCompiler.compileSymbolRef(JvmExprCompiler.java:85)
WASM: java.lang.UnsupportedOperationException: Cannot compile symbol: *k*
        at codegen.wasm.WasmExprCompiler.compileSymbolRef(WasmExprCompiler.java:99)
```

## Impact / current workaround

The new `examples/nqueens.lisp` and `examples/mandelbrot.lisp` thread the value
(`n`, `max-iter`) through as a function argument so they compile on all three
backends. That is itself idiomatic CL, so it is shipped as-is -- but the global
special variable form is also idiomatic and should compile. (Existing
`examples/nn.lisp` / `mlp.lisp` likewise only touch their `defparameter` globals
at top level.)

## What to implement

In `JvmExprCompiler.compileSymbolRef` / `WasmExprCompiler.compileSymbolRef`,
when a bare symbol is not a local and is not resolvable as a lexical, fall back
to the global variable namespace that top-level `defparameter`/`setq`/lexical
references already use (interpreter parity: `Environment.lookup`). The compilers
already keep a global value environment for `eval` (JVM `_genv`, WASM
`GLOBAL_ENV`); decide whether compiled globals should share that store or a
dedicated one, and make `setq` of a global from inside a function write back to
the same place.

Verify on interpreter / JVM / WASM and add an E2E case to `ci-spec.yaml`. Once
this lands, `examples/nqueens.lisp` / `mandelbrot.lisp` may optionally be
rewritten to read the global directly if that reads more naturally.
