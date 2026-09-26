# A wrong-type operand is a simple-error on the wasm backends

Difficulty: Medium

`(handler-case (+ 1 nil) (type-error (e) (type-error-datum e)))` matches on the interpreter and
the JVM, but on wasm-GC (Preview 1 and `--component`) the `_type_err_*` landing throws an
instance-less `(nil . message)` payload, so it is caught as a `simple-error`: a `type-error`
clause or `handler-bind` does not match and `type-error-datum` is unavailable. The text is
already identical (`OP: The value X is not of type T`, `.kb/error-handling.md`, "A non-number
reaching arithmetic"); only the class diverges.

The landing is a fixed helper built after `mayCreateInstances`/`usedLayoutTags` decided, so it
cannot construct the instance itself. A shape to start from, after the `%program-error`
precedent (`LispMacroExpander.lowerProgramError`, baked under `establishesLandingPad`): when
the program has a landing pad AND spells `type-error`, `type-error-datum` or
`type-error-expected-type`, inject a runtime defun `(%operand-type-error datum type message)`
that signals `(%error-cond <type-error instance> message)` through
`reportingConditionForm(..., {DATUM, EXPECTED-TYPE})`, bake the `TYPE-ERROR` layout in
`usedLayoutTags` on the same answer, and have `WasmOperandTypes.buildLandingBody` call it with
the operand, the type symbol and the rendered message instead of throwing the payload itself.
Measure the EH-mode size on zlib before and after.

Done when `ci-spec.yaml` can catch these with a `type-error` clause and read the datum on all
four backends, and the kb's "Class divergence" bullet is gone.
