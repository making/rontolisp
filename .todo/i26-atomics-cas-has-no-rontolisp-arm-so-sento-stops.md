# i26. atomics:cas has no rontolisp arm, so sento stops

Difficulty: Medium

Measured 2026-11-04 after `.todo/h97`. `(ql:quickload "sento")` loads on the interpreter, and the first
`act:ask` stops:

```
Unhandled condition:
The CAS operation is not supported by rontolisp in Atomics.
  at .../atomics-20260101-git/atomics.lisp:51 in ATOMICS::NO-SUPPORT
```

The compile paths stop at the same text while they compile, at
`cl-gserver-20260101-git/src/atomic/atomic.lisp:30:3`: the macro signals as it expands. sento's use is
`(atomics:cas (car (slot-value ref 'cell)) old new)`.

atomics keys `cas` and its `:atomics-cas-*` features on `#+sbcl` and the like, with `no-support` for any
other implementation. It needs a shim or a rontolisp arm over a real compare-and-swap, at least on `car`:
threads are real on the interpreter and the JVM. What sento needs past it is unmeasured.
