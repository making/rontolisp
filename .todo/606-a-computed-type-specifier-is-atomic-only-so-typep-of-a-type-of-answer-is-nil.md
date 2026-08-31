# A computed type specifier is atomic-only, so (typep a (type-of a)) is nil for an array

Difficulty: Medium

Fell out of `.todo/604`, which taught `type-of` to answer the COMPOUND array
specifier. Against SBCL 2.2.9:

```lisp
(let ((a (make-array 4)))            (typep a (type-of a)))  ; NIL  SBCL: T
(let ((a (make-array nil)))          (typep a (type-of a)))  ; NIL  SBCL: T
(let ((a (make-array 4 :element-type 'double-float)))
  (typep a (type-of a)))                                     ; NIL  SBCL: T
(let ((s '(integer 0 10))) (typep 5 s))                      ; NIL  SBCL: T
```

The LITERAL spelling is right on all four backends -- `(typep a '(simple-vector
4))` answers T -- so this is not the array lattice. It is the RUNTIME specifier
path: `LispMacroExpander.expandRuntimeTypep` (and the `%typep-runtime` defun the
compile paths call instead) is a `cond` keyed on the specifier SYMBOL, one arm
per registered class/struct/condition plus `RUNTIME_TYPEP_BUILTINS`, and a
specifier that arrives as a CONS matches no arm, so it falls to the closing
`(t nil)`. The class comment says so ("Deliberately the atomic names only"),
which was defensible while nothing in the language HANDED a program a compound
specifier; `type-of` now does, and `(typep x (type-of y))` is a normal idiom.

Shape of the fix: give the runtime dispatch a CONS arm that reads the head and
routes the common compound families -- the array family (`array`/`simple-array`/
`vector`/`simple-vector`, whose test is `LispMacroExpander.makeArrayTypeTest`),
`(or ...)`/`(and ...)`/`(not ...)` recursing, `(member ...)`/`(eql ...)`, the
ranged numerics and `(unsigned-byte n)`/`(signed-byte n)`. Every one of those
tests already exists as a STATIC builder over a value form; what is missing is a
runtime interpreter that picks the arm from the head symbol and reads the
arguments out of the specifier VALUE rather than out of the AST. Do it once, in
the shared `%typep-runtime` defun, so the inline interpreter path and the
compile paths cannot drift; the JVM branch-offset overflow that forced
`%typep-runtime` into existence is the reason not to inline a wider cond.

Watch `subtypep`, which takes the same computed specifiers and has the same
atomic-only shape.

Behavior must be identical on all four backends
(`.kb/declarations-type-checks.md` owns the lattice and names the pinning
tests): rows in `LispEvaluatorTest` + `JvmLispCompilerTest` +
`WasmLispCompilerIntegrationTest` and a ci-spec case.
