# i02. `(setf (apply #'f args) value)` for any function

Difficulty: Low

Measured 2026-11-04, interpreter and JVM:
`setf does not support place: APPLY (only (setf (apply #'aref ...)) is supported)`.

CLHS 5.1.2.5 defines this place for every `#'name`. With `(defun (setf fa) (v a b &key k) ...)`,
`(setf (apply #'fa '(1 2 :k 3)) 9)` answers `(:SET 9 1 2 3)` on SBCL: the new value goes first, the
spread arguments after it.

Consumer: caveman2. `defroute` spells `(setf (apply #'ningle:route ...) ...)` in all three of its
expansions (`v2/src/route.lisp:94,102,136`), so no route can be defined. With the named-route one
rewritten in the walked copy, caveman2 answers `(200 ... ("hi bob"))` and the 404 as SBCL does, on
the interpreter.

Lower it as `(apply #'(setf f) value args)`, keeping evaluation order (the arguments, then the
value). `.kb/cffi.md` holds the existing `aref` row-major case. All four backends, a `ci-spec.yaml` case.
