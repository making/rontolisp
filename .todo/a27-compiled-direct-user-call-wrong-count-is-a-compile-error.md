# A direct call of a program's own function with a wrong count fails the compile

Difficulty: Medium

A DIRECT call of a user function with a count its lambda list rules out:

| form | interpreter | JVM / wasm |
|---|---|---|
| `(defun ud (a b) ...)` `(ud 1)` | `Function expects 2 arguments, got 1` (program-error) | compile error `UD expects 2 arguments, got 1` |
| a built-in shadowed by `defmethod length`, `(length x 2)` | program-error | compile error `%LENGTH--dispatch expects 1 argument, got 2` |

A program whose wrong-count call sits in a branch it never takes, or under a `program-error`
handler (the ANSI suite's `signals-error` rows), runs interpreted and does not compile.

Goal: as a direct built-in call already does (`compiler/BuiltinCallArity`, `.kb/error-handling.md`,
"A DIRECT call of a built-in"): evaluate the arguments, signal the interpreter's text at run time,
warn at compile time. The JVM's refusal sits in the direct-call path (`JvmFunctionCallCompiler`),
wasm's in its twin; the generic-dispatch rename (`ShadowedBuiltins`) refuses separately. Note the
wasm rest-bundled defun (>10 params) already reports at run time (`.kb/wasm-callable-arity.md`).
Pin in `ci-spec.yaml`.
