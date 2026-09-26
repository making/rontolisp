# `rontolisp:await` returns only the primary value of an async body

Difficulty: Medium

An `async-defun` whose last form is `(values a b c)` settles its future with `a` alone, and
`(multiple-value-bind (x y z) (rontolisp:await (f)) ...)` binds `y`/`z` to NIL -- silently, on
every backend:

```lisp
(rontolisp:async-defun f () (values 1 2 3))
(print (multiple-value-list (rontolisp:await (f))))   ; (1)
```

Before acf4b247a (2026-09-19) this printed `(1 2 3)`, but only by accident: the async body
wrote the process-wide `Environment.mvSpill` on its virtual thread and the awaiting side
happened to read it back -- a race once two bodies run at once. acf4b247a made `await`
single-valued (`singleValue(awaitValue(...))` in `LispEvaluator`) and `.kb/multiple-values.md`
now states it, but `doc/*/guides/async.md` and the `rontolisp:await` reference do not, and a
program written against the old behaviour fails far from the cause (a NIL reaching `>`).

Goal: an async body's values reach the awaiter, the way a function call's do. The future
settles with the full value list (captured on the body's own thread, not through the shared
spill) and `await` returns them as multiple values -- interpreter, JVM and both wasm backends,
suspending and non-suspending awaits alike, pinned by one cross-backend test. If measurement
says the wasm/JVM cost is not worth it, the fallback is the opposite: keep one value, document
it in the async guide and the reference, and warn at compile time when an async body's tail
form is `values`. Either way, no value may vanish silently.

Read first: `.kb/multiple-values.md`, `.kb/async-await.md`.
