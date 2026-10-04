# d02. A global assigned only inside a defun is no compile-path global

Difficulty: Medium

```lisp
(defun s () (setq *z* 1))
(defun r () *z*)
(s)
(print (r))
(print (boundp '*z*))
```

The interpreter and SBCL (with an undefined-variable warning) print `1` and `T`. The JVM and
both WASM refuse `R` (`Cannot compile symbol reference: *Z*`); without `R` they compile and
`(boundp '*z*)` answers NIL. A top-level read of `*z*` refuses the same way. The name is
assigned only inside a deferred body, so `GlobalVarCollector` gives it no backing store, while
`CompileTimeBoundp` leaves the probe to a run time where nothing stored it.

Assigning an undeclared variable is undefined in CL, so the fix is a decision first: make such
a name a global on the compile paths (what SBCL does), or refuse the `setq` at compile time
with a message naming the missing `defvar`. Either way the three-way split goes.
