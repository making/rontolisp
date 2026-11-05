# i55. Compiled `eval` of an unbound symbol answers the symbol

Difficulty: Medium

Measured 2026-11-05. SBCL 2.2.9 and the interpreter signal `unbound-variable` for both forms. The JVM and both WASM
backends do not:

```lisp
(eval 'no-such-var)        ; compiled: NO-SUCH-VAR
(eval '(+ 1 no-such-var))  ; compiled: "+: The value NO-SUCH-VAR is not of type NUMBER"
```

`JvmEvalRuntimeBuilder.evalBody` says so ("evaluates to ITSELF under its original spelling") after retrying the
case-flipped spelling, and the WASM eval is its twin. Before signalling, find out what relies on it: a name a
compiled program defines and the eval mirror does not hold (`.kb/eval-runtime.md`) would turn from a silently wrong
value into an error, so the mirror's coverage has to be measured over the library corpus first.
