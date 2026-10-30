# h73. arnesi's `src/asdf.lisp`: `asdf:operation-done-p`, `compile-bundle-op`, `uiop:copy-file`

Difficulty: Low

Found 2026-10-31 probing `djula` after h72 (interpreter, `(ql:quickload "djula")`): arnesi's
`src/asdf.lisp` stops the load with
`The symbol OPERATION-DONE-P is not external in the ASDF package`.
It defines a `clean-op` operation and `(defmethod asdf:operation-done-p ((op clean-op) (c asdf:component)) ...)`,
and `make-single-fasl` names `asdf:compile-bundle-op` and `uiop:copy-file` in a body loading
never runs. None of the three is known here (`PackageRegistry` asdf exports,
`asdf.lisp` runtime, `.kb/asdf.md`).

Add `operation-done-p` as an `asdf.lisp` generic (default method answering t), export
`compile-bundle-op` (a class under `operation`, or resolve-only like `missing-component`) and
`uiop:copy-file`, on every backend the asdf runtime serves. Then probe further and record the
next blocker in `.todo/152`.
