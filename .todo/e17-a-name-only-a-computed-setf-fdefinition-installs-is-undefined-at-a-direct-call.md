# e17. A name only a computed `(setf fdefinition)` installs is undefined at a direct call

Difficulty: Medium

```lisp
(defun place-name (p) (list 'setf p))
(setf (fdefinition (place-name 'zz-n)) (lambda (v x) (list v x)))
(print (funcall (fdefinition (place-name 'zz-n)) 1 2))
(print (setf (zz-n 9) 8))
(setf (fdefinition (intern "ZZ-F")) (lambda (x) (* x 2)))
(print (zz-f 4))
```

SBCL 2.2.9 and the interpreter: `(1 2)`, `(8 9)`, `8`. JVM, P1 and component: `(1 2)`, then the
compile-time warning `the function (SETF ZZ-N) is undefined; compiled as a run-time error` and
the call-time `undefined-function` (a trap on wasm outside EH mode); the symbol `ZZ-F` the same.
A QUOTED name gets the setf-only forwarder defun (`setfOnlyFunctionAliasNames`,
`.kb/symbol-runtime-api.md`), whose body reads `_fenv` / `GLOBAL_FENV`; a computed one cannot
be known when the program compiles.

## Plan

- Four-backend fixture first (a symbol and a `(setf name)` list, each installed through a
  computed name and called directly, as a place, with `#'`; an undefined name still reports).
- Where the program can write the function namespace through a computed name, the call-time
  stub of a name with no definition could read the namespace (`%fenv-function`'s read) before
  signalling; keep a program without such a write byte-identical.
