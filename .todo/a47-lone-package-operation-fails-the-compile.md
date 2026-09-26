# A program whose only package operation is delete-package / shadow / unintern fails to compile

Difficulty: Medium

Measured 2026-09-26 on the JVM (the wasm and component builds fail the same way):

| program | compile |
|---|---|
| `(print (delete-package "X"))` | `while compiling defun LIST-ALL-PACKAGES: Cannot compile: STRING<` |
| `(print (shadow 'foo))` | `while compiling defun %BAKED-PACKAGE-FIND: Cannot compile symbol reference: %BAKED-PACKAGES%` |
| `(print (shadowing-import 'foo))` | same |
| `(print (unintern 'foo))` | same |
| `(make-package "X") (print (delete-package "X"))` | compiles, prints `T` |

The interpreter runs all of them. The library splice these operators trigger reaches a defun
(`list-all-packages`, `%baked-package-find`) whose own dependencies (`string<`, the
`%baked-packages%` table) are spliced only when some OTHER package operation is in the program.
The right count fails as much as a wrong one.

Goal: each operator compiles on its own on every backend; pin a one-operator program per name
next to the package cases in `ci-spec.yaml`.
