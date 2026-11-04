# i14. `(funcall 'n ...)` keeps the old definition after `(compile 'n def)` or `(setf fdefinition)`

Difficulty: Medium

Measured 2026-11-04. For a name the program `defun`s, after `(compile 'n def)` or
`(setf (fdefinition 'n) ...)`, `(funcall 'n 1)` calls the OLD definition on JVM, WASM preview 1 and
component. The interpreter and SBCL call the new one. `(funcall (intern "N") 1)` calls the new one on
every backend.

This contradicts `doc/en/reference/functions/compile.md` ("a call through the symbol sees the new
one"; the `doc/ja` counterpart is unchecked). `.kb/symbol-runtime-api.md`, Divergences, lists only the
eagerly-bound call sites, not this quoted form. The forwarder for a literal `(compile 'name def)`
excludes a `defun`'d name, so it should not have changed this; the pre-forwarder jar was not measured.
