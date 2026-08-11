# declare

`(declare declaration...)`

Declarations never change what a program computes: the whole `declare` form evaluates to nil and its arguments are never evaluated or validated, so any standard declaration (`ignore`, `ignorable`, `type`, `optimize`, `inline`, `special`, ...) is accepted anywhere in a body, and source code written for other Common Lisp implementations loads unchanged.

Two declaration families do affect compilation. A `(declare (special ...))` marks a variable dynamically scoped, as in standard CL. And on the WASM backend, a `type` declaration that names an array type -- `(simple-array (unsigned-byte 8) (*))`, `simple-vector`, `simple-string`, ... -- lets the compiler emit that one representation's element accessors directly, which makes the compiled module smaller and faster. Results are unaffected by a *correct* declaration; a *false* one, which is undefined behavior in Common Lisp, traps at the access on WASM while the other backends continue to ignore it.

```lisp
(let ((x 10))
  (declare (type integer x) (optimize (speed 3)))
  (* x 2)) ; => 20
```
