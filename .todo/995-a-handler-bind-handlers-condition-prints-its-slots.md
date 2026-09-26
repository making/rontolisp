# `~a` of the condition a `handler-bind` handler receives prints its slots on the compiled backends

Difficulty: Medium

Measured 2026-09-26:

```lisp
(defun main ()
  (handler-bind ((error (lambda (c)
                          (format t "saw ~a~%" c))))
    (car 5)))
(print (ignore-errors (main)))
```

- Interpreter: `saw CAR: The value 5 is not of type LIST` -- the condition's report, as `princ`
  and `~a` print one (`.kb/error-handling.md`, "A condition's `:report` is what PRINTS it").
- JVM and wasm-GC: `saw #<TYPE-ERROR :DATUM 5 :EXPECTED-TYPE LIST :FORMAT-CONTROL CAR: The value 5
  is not of type LIST :FORMAT-ARGUMENTS NIL>`.

The same program with a `handler-case` whose clause binds and uses its variable prints the report
on all three, so the routing gate that decides whether the printers route a condition through its
report ("The routing gate asks whether a condition can be NAMED") does not count a `handler-bind`
handler as a holder, though every handler is called with the instance.

Goal: the report on every backend, pinned in `ci-spec.yaml`, with the size of a program that has
no `handler-bind` unchanged.

Read first: `.kb/error-handling.md` ("A condition's `:report` is what PRINTS it", "The routing
gate asks whether a condition can be NAMED", "Phase 4 -- handler-bind + the restart stack").
