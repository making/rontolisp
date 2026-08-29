# declare

`(declare declaration...)`

Declarations never change what a program computes: the whole `declare` form evaluates to nil and its arguments are never evaluated or validated, so any standard declaration (`ignore`, `ignorable`, `type`, `optimize`, `inline`, `special`, ...) is accepted anywhere in a body, and source code written for other Common Lisp implementations loads unchanged.

Three declaration families do affect compilation. A `(declare (special ...))` marks a variable dynamically scoped, as in standard CL. On the WASM backend, a `type` declaration that names an array type -- `(simple-array (unsigned-byte 8) (*))`, `simple-vector`, `simple-string`, ... -- lets the compiler emit that one representation's element accessors directly, which makes the compiled module smaller and faster. And on the JVM backend, a `type` declaration that names a float type -- `double-float`, `single-float`, or `float` -- keeps the declared local variables in raw `double` slots and compiles arithmetic over them as unboxed machine instructions, which makes the compiled class faster, especially before the JIT warms up. Results are unaffected by a *correct* declaration; a *false* one, which is undefined behavior in Common Lisp, traps at the access on WASM and signals a catchable type error at the declared read or store on the JVM, while the interpreter continues to ignore it.

```lisp
(let ((x 10))
  (declare (type integer x) (optimize (speed 3)))
  (* x 2)) ; => 20
```
