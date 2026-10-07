# d94. A standard generic function as a value refuses on the compile paths without a method

Difficulty: Medium

```lisp
(print (functionp #'initialize-instance))
```

Interpreter and SBCL: `T`. The JVM, P1 and the component refuse the program with `Cannot
compile: INITIALIZE-INSTANCE as a function value (this backend has none for the built-in)` --
the same for `reinitialize-instance`, `shared-initialize` and `make-load-form` -- unless the
program defines a method on the name or otherwise carries the CLOS runtime. With that runtime the
values diverge: `(funcall #'initialize-instance 1)` answers `1` on the JVM where the interpreter
signals `%MOP-FILL-SLOTS expects (instance initargs initforms-p)` and SBCL `no-applicable-method`.
`#'make-load-form` signals `undefined-function` on the interpreter, a generic function in SBCL.
`BuiltinFunctionWrapperCatalogTest.USER_DEFINED_GENERICS` and
`StandardFunctionValueCompileTest.NO_VALUE` exclude the four.

## Plan

- Four-backend fixture first (value, `functionp`, a call with no applicable method).
- Decide the value of a standard generic with only its standard methods on every backend
  (`.kb/clos.md`), then drop the four from both exclusion lists.
