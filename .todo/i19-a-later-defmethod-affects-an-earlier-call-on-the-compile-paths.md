# i19. On the compile paths a later `defmethod` affects an earlier call

Difficulty: Medium

Measured 2026-11-04.

```lisp
(make-instance 'h9)
(defmethod initialize-instance :after ((x h9) &key) (print :init))
```

JVM and WASM (preview 1, component) print `:INIT` on the earlier `make-instance` too. The interpreter
does not, and SBCL agrees with the interpreter on a similar case in a separate program. The static
dispatcher is the suspected cause, not yet confirmed.
