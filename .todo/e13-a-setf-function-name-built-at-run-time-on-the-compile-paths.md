# e13. A `(setf name)` function name built at run time is refused on the compile paths

Difficulty: Medium

```lisp
(defun (setf zz-c) (v x) (list v x))
(defun place-name (p) (list 'setf p))
(print (funcall (fdefinition (place-name 'zz-c)) 1 2))
(print (fboundp (place-name 'zz-c)))
```

SBCL and the interpreter: `(1 2)`, then true. JVM: a raw `ClassCastException` (`Object[]` to
`String`); P1 and the component: `Not a function: (SETF ZZ-C)` and a trap. The same for
`fmakunbound` and `(setf (fdefinition <computed>) fn)`. A QUOTED `(setf name)` works on every backend:
`FunctionDesignators.normalizeBuiltinDesignators` maps it onto the writer's `%setf-NAME` at compile
time (`.kb/symbol-runtime-api.md`, "A `(setf name)` function name").

## Plan

- Four-backend fixture first (each operator, a defined, an undefined and a `fmakunbound`-retired
  name; the undefined one reports `(SETF NAME)`).
- Map the list where each backend reads a computed designator: the JVM can build the
  `%setf-` string; wasm compares symbols by string-table offset, so the built name must go
  through `_intern` (the `usesIntern` rail). Keep a program without a computed designator
  byte-identical; measure what a site that has one costs before choosing per-site code versus
  one shared runtime helper.
