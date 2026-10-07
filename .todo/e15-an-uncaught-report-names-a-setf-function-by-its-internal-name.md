# e15. An uncaught report names a `(setf name)` function by its internal name

Difficulty: Low

```lisp
(defun (setf zz-w) (v x) (declare (ignore x)) (car v))
(funcall #'(setf zz-w) 1 2)
```

Interpreter and JVM:

```
Unhandled condition: CAR: The value 1 is not of type LIST
  at t.lisp:1 in %setf-ZZ-W
```

SBCL's backtrace names the frame `(SETF ZZ-W)`. The undefined-function text and condition already
spell `(SETF NAME)` (`ClosRegistry.functionNameForReport`); the location line's function name
(`UncaughtReport.atLine`, `JvmUncaughtHandler`, `WasmUncaughtLocations`) does not.

## Plan

- Pin the location line on every backend that prints one (`UncaughtReportParityTest`), then spell
  the name through `functionNameForReport` where each backend records it.
