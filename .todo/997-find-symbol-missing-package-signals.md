# find-symbol into a missing package answers nil instead of signalling

Difficulty: Medium

CLHS `find-symbol` takes a package designator; one naming no package is an error (SBCL:
`package-does-not-exist`, a `package-error`). Measured 2026-09-26, all four backends:

```lisp
(print (handler-case (find-symbol "X" "NOPKG") (error () :caught)))   ; NIL, expected :CAUGHT
```

The nil answer is deliberate today (`.kb/symbol-runtime-api.md`, the interpreter's
`findSymbolAccessible`; the compiled lowerings fold an unknown literal package to nil and guard
a computed one). `intern` now signals through `%package-error` (`lowerPackageError`, the
`PACKAGE_ERROR_SITES` scan hook), which `find-symbol` / `%find-symbol-status` can reuse. Before
changing it, check which library code relies on the nil answer (uiop's `find-symbol*`,
alexandria, the ANSI `find-symbol` rows) and pin the result in `ci-spec.yaml`.
