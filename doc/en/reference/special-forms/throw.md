# throw

`(throw tag result)`

Transfers control to the innermost active [`catch`](catch.md) whose tag is `eq` to `tag`, making `result` that `catch` form's value. The stack is really unwound, so every intervening [`unwind-protect`](unwind-protect.md) cleanup runs on the way out (innermost first) -- and a `handler-case` in between does **not** intercept it, because a `throw` is a non-local exit, not a signaled condition.

`throw` is an error when no matching `catch` is active: the interpreter reports `THROW: no enclosing catch for tag ...`, the JVM backend raises the equivalent runtime error, and the wasm-GC backends trap (the same way an uncaught `error` does). The `result` form is evaluated before the unwind starts.

```lisp
(let ((log nil))
  (list (catch 'up
          (unwind-protect (throw 'up :out) (setq log (cons :cleaned log))))
        log)) ; => (:OUT (:CLEANED))
```

A `throw` unwinding through a `handler-case` is not caught by it:

```lisp
(catch 'up (handler-case (throw 'up :through) (error (e) :caught))) ; => :THROUGH
```

Because an unmatched `throw` aborts the program, that path is shown statically:

```console
> (throw 'nope 1)
Error: THROW: no enclosing catch for tag NOPE
```
