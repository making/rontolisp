# The interpreter ignores a defmethod on a built-in it expands; the compiled backends dispatch it

Difficulty: Medium

```lisp
(defclass bx () ())
(defmethod byte-size ((b bx)) 42)
(print (byte-size (make-instance 'bx)))
```

| name | interpreter | JVM / wasm (2026-09-26) |
|---|---|---|
| `byte-size` | `CAR: The value #<BX> is not of type LIST` | `42` |
| `byte-position` | `CDR: The value #<BX> is not of type LIST` | `42` |
| `realpart`, `numerator`, `char-code`, `symbol-name`, `conjugate` | `42` | `42` |

`LispEvaluator`'s tail-transparent operator table expands `byte-size` / `byte-position`
(`builtinMacroExpansion(cons, LispMacroExpander::expandByteSize)`) before the global function
binding -- the user's dispatcher -- is consulted. `ShadowedBuiltins.EXPANSION_LOWERED` is the list
of names the compile path must NOT shadow for exactly this reason, and `ShadowedBuiltinsTest` pins
it by asking whether each kept name is a `LispFunction` in a fresh environment: these names are,
yet `evalCons` never reaches the binding. The same table expands `byte`, `ldb`, `dpb`,
`make-sequence` and others; which of them are also shadowable on the compile path is not measured.

Goal: one answer on all four backends. Either the interpreter honours the user dispatcher (the
`LispLambda` global-binding check `LispEvaluator.wrongCountCall` already makes) before expanding,
or the compile path stops shadowing these names. Make the pinning test see an `evalCons`
expansion, not only the binding.
