# i37. fset's port layer has no rontolisp arm, so coalton stops

Difficulty: Medium

Measured 2026-11-05 on the interpreter. `(ql:quickload "coalton")` passes eclector and stops:

```
The function FSET::MAKE-LOCK is undefined
  at .../fset-v2.2.0/Code/order.lisp:112
```

`Code/port.lisp` defines `make-lock`, `with-lock` and the memory barriers per implementation (`#+sbcl`, `#+ccl`,
...), with no fallback. It needs a shim or an arm, as `.todo/i26` does for atomics; threads are real on the
interpreter and the JVM. What coalton needs past it is unmeasured.
