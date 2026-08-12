# The component async lowering leaks through a synchronous caller

Difficulty: High

Found while fixing the Preview 1 export boundary (todo 341, which resolves a
returned future in the wrapper). Two findings on the `--component` asyncMode
path, measured with wasmtime 47; the second is what MASKS the first in the
simplest spelling, so they have to be fixed together.

## 1. An export whose target merely PASSES a future through traps

`WasmExportCompiler.emitBody`'s poll / `_sched_loop` branch is gated on
`ctx.asyncDefunNames.contains(decl.name())` -- the target being a top-level
`rontolisp:async-defun`. A plain defun that hands back someone ELSE's future has
exactly the same boundary problem, and there the wrapper unboxes a
`TYPE_FUTURE` as the declared scalar:

```lisp
(rontolisp:async-defun inner (n) (print "x") (+ n 100))  ; multi-form: not inlinable
(defun probe (n) (inner n))
(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
```

`wasmtime run -W gc=y -W exceptions=y --invoke 'probe(7)'` answers
`wasm trap: cast failure`. Preview 1 answers 107 since todo 341.

The block is ALREADY fully dynamic (`_poll` passes a non-future through, and the
`ref.test` decides whether to drive `_sched_loop`), so the fix looks like
dropping the static gate to `ctx.asyncFuncBase >= 0` -- at the cost of the
poll sequence in every asyncMode component export, which the byte-identity
discipline wants weighed. The P1 side spends 2 bytes for the same guarantee
because `_p1_future_await` needs no branch; the component's does.

## 2. A one-form async-defun is INLINED, so `futurep` answers NIL

The same program with an inlinable body does not trap -- because the future is
never built:

```lisp
(rontolisp:async-defun inner (n) (+ n 100))
(defun probe (n) (if (rontolisp:futurep (inner n)) 1 0))
(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
```

answers `0` under `--component` and `1` on Preview 1 (and T on the interpreter
and the JVM). The defun inliner splices `inner`'s BODY into the caller,
bypassing the entry+resume state machine `rewriteTopLevelAsyncDefuns` /
`WasmAsyncEmit` built for it -- so calling an async function from synchronous
code yields the raw value where every other backend yields a future. An
async-defun's name must be excluded from the inlinable set (or the inline must
reproduce the entry, which is the state machine, not the body).

## Verification

- Both programs above answer identically on all four backends (interpreter, JVM,
  Preview 1, `--component`), `futurep` included.
- A component with no async surface stays byte-identical; state what an
  asyncMode component's exports cost.
- The `--component` async integration tests
  (`WasmLispCompilerIntegrationTest.component*`) and the serve/callback shapes
  are unchanged -- `handle` takes the callback branch, not this one.
