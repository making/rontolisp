# The JVM's cons accessors accept any `Object[]`: an instance, a ratio or a function answers a slot

Difficulty: Medium

Measured 2026-09-26 (JVM only; the interpreter and both wasm-GC signal a `type-error` for every
line):

```lisp
(defun id (x) x)
(defstruct pt x y)
(let ((s (make-pt :x 1 :y 2)))
  (car (id s))                 ; JVM: (|%struct-PT| . PT) -- the layout
  (endp (id s))                ; JVM: NIL
  (dolist (x (id s)) ...)      ; JVM: zero iterations, no error
  (rplacd (id s) 9)            ; JVM: #S(PT :X 9 :Y 2) -- overwrites slot X
  (car (id 1/2))               ; JVM: 1
  (car (id #'id)))             ; JVM: an Integer (the funcref's index)
```

`(car c)` on a condition in a `handler-bind` handler therefore returns instead of failing, which is
why the report of todo 994's program (`(car c)` inside the handler) still reads `Unhandled
condition: first` on the JVM; the ci-spec case `failing-handler-bind-handler-report` spells the
failure `(car (type-of c))` to stay off this bug.

Cause: `_car`/`_cdr`/`_endp`/`_ckList`/`_ckCons` (`JvmOperandTypeRuntime`) test `instanceof
Object[]` only. A cons, an instance (`String[]` layout in slot 0), a funcref (`Integer` in slot 0),
a ratio (`BigInteger[]`) and an async value (`Object[3]`, marker in slot 0) all pass; `consp` /
`listp` / `atom` exclude them (`JvmEmitHelper.emitInstanceExclusion` /
`emitAsyncValueExclusion`, the ratio and funcref tests in `JvmConspCompiler`).

Goal: the interpreter's `type-error` on the JVM for every row, pinned in `ci-spec.yaml`.

Cost is the question: `car`/`cdr` are the hottest calls, and every `Object[]` takes the cons path,
so the exclusion has to sit on the fast path (gated like `consp`'s on `Ctx.mayUseInstances` /
`mayUseAsyncValues`) or the representation has to change. `.kb/core-representation.md` records a
~4% cdr-walk loss for a representation change; measure a cdr walk and `mapcar` before and after.
