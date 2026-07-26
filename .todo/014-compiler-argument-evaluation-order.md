# 14. Compilers evaluate call/`list` argument forms right-to-left

## Problem

Common Lisp specifies that the argument forms of a function call (and the
elements of a `list` form) are evaluated **left-to-right**. The interpreter does
this; the **JVM and WASM compilers evaluate them right-to-left**, so any program
whose argument forms have side effects observes a different order on the
compiled backends than on the interpreter.

The result value is usually unaffected (a call/`list` assembles its arguments
positionally regardless of evaluation order), so this only bites when the
argument forms mutate shared state. It surfaced while writing the `make-array`
tests: `(list (funcall *bump* 0) (funcall *bump* 0) (funcall *bump* 1))`, where
`*bump*` increments a captured array cell, yields `(1 2 1)` on the interpreter
but `(2 1 1)` on the compilers.

## Reproduction (all four backends)

```lisp
(defun make-noter (buf pos)
  (lambda (x)
    (setf (aref buf (aref pos 0)) x)
    (setf (aref pos 0) (+ 1 (aref pos 0)))
    x))
(defparameter *buf* (make-array 3 :initial-element 0))
(defparameter *pos* (make-array 1 :initial-element 0))
(defparameter *note* (make-noter *buf* *pos*))
(defparameter *r* (list (funcall *note* 1) (funcall *note* 2) (funcall *note* 3)))
(format t "result=~a order=~a~a~a~%" *r* (aref *buf* 0) (aref *buf* 1) (aref *buf* 2))
```

Observed:

| Backend          | `order` | meaning           |
| ---------------- | ------- | ----------------- |
| Interpreter      | `123`   | left-to-right (correct) |
| JVM              | `321`   | right-to-left     |
| WASM Preview 1   | `321`   | right-to-left     |
| WASM component   | `321`   | right-to-left     |

(`result` is `(1 2 3)` on every backend — only the side-effect order differs.)

## Expected

All backends evaluate argument forms left-to-right, matching the interpreter and
Common Lisp. Target: `order=123` everywhere.

## Where to look

- JVM: the call/`list`-argument emission in `codegen.jvm` (the function-call
  compiler and `JvmListCompiler`/`compileCons` argument loop) — arguments are
  almost certainly pushed in reverse so they land on the operand stack in the
  wanted order; that reversal also reverses the *evaluation* order. The usual
  fix is to evaluate each argument left-to-right into a temp local, then load the
  temps in stack order.
- WASM: the corresponding argument emission in `codegen.wasm`.
- Likely affects all multi-argument call forms, not just `list` — audit `+`,
  `format`, user `defun` calls, `funcall`, etc. A pure (side-effect-free)
  argument is unaffected, which is why this stayed latent.

## Tests / verification

- Add a cross-backend `ci-spec.yaml` case built from the reproduction above (it
  is side-effect-ordering sensitive and deterministic), expecting `order=123` on
  all four backends. Run `CiSpecE2eTest` against the native binary.
- The `make-array` tests that touch this
  (`compileArrayCapturedInClosure` in the JVM/WASM tests,
  `arrayCapturedInClosure` in `LispEvaluatorTest`, and the
  `arrays-cross-backend` ci-spec case) were deliberately written to be
  evaluation-order *independent* (each step sequenced through a top-level
  `defparameter`). Once this is fixed they can be simplified back to the natural
  single-form `(list (funcall ...) (funcall ...) (funcall ...))` shape.

## A second field sighting (2026-07-26, from the cl-postgres component work)

The `--component` socket layer surfaced the same defect and it was briefly
misfiled as an async-scheduler bug (the deleted `.todo/176`, finding 1): at the
top level, `(print (list (rb sock) (rb4 sock) (rb4 sock)))` -- each helper a
plain defun doing sequential `read-byte`s -- consumed the wire bytes in
REVERSE argument order, exactly this right-to-left evaluation (a socket read is
an argument form with side effects). Note the contrast that proves it is not
the async machinery: reads PROMOTED to `rontolisp:await` in the same position
are hoisted into sequenced bindings by `WasmAwaitNormalizer` and observe
left-to-right order correctly; only the un-promoted (plain-call) arguments
reverse, on the JVM and both WASM backends alike.

## Related observation (separate, unverified)

While probing this, a closure that captures a free variable named `log` (a `cl`
function name) failed to compile on the JVM with
`Cannot compile symbol reference: log` (from `JvmExprCompiler.compileSymbolRef`).
Renaming the variable avoided it. This looks like an independent free-variable /
builtin-name interaction in the compiler, not an evaluation-order issue — worth
its own investigation if confirmed.
