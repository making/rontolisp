# i11. A top-level dotted progn over `#.` does not compile

Difficulty: Low

Measured 2026-11-04.

```lisp
(progn . #.(list '(print 1) '(print 2)))
```

The interpreter prints 1 and 2. `-o Prog.class` stops with `Cannot compile symbol reference: %READ-EVAL`.
The same dotted `#.` inside a form compiles (`(list 1 . #.(list 2 3))`, a `defun` body, a `defgeneric`),
so only the top-level `progn` is affected. Check `UserMacroExpander`'s top-level walk, which reads a
`progn`'s members before the marker is resolved.

Consumer: `concrete-syntax-tree`, `declarations.lisp:48`. It loads on the interpreter but compiles no
program past this form on JVM; WASM is unmeasured.

Pin in `ci-spec.yaml`.
