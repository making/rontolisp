# 63: Compiled `print` (and friends?) returns nil instead of its argument

**Status:** pre-existing cross-backend divergence, found 2026-07-05 while
testing the multiple-values unit (`.kb/multiple-values.md`). Reproduced on a
clean pre-change build (HEAD = 02bcfde), so NOT introduced by that unit.

## Symptom

CL (and the interpreter): `print` outputs its argument and **returns it**.
The JVM and WASM compile paths return nil instead:

```lisp
(print (print 11))          ; interpreter: 11 11   JVM/WASM: 11 nil
(let ((x (print 11)))
  (let ((a x)) (print a)))  ; interpreter: 11 11   JVM/WASM: 11 nil
```

Any code that uses the return value of `print` in an init/argument position
silently gets nil on the compile paths. Verified on interpreter vs JVM class
vs WASM Preview 1 (component not separately checked, presumably identical to
Preview 1).

## Scope to check

- Which printers: `print` confirmed; check `prin1`/`princ`/`terpri`/
  `fresh-line`/`write-line` for their CL-specified return values too
  (`terpri`/`fresh-line` return nil in CL, so those are likely fine;
  `write-line` returns its string).
- Both the direct call-position compilation (`Jvm/Wasm<Print>Compiler`) and
  the first-class wrappers (`#'print` via `BuiltinFunctionWrappers`, whose
  body is the call-position form, so fixing codegen fixes both).
- ci-spec cases never rely on the return value today (that is why this never
  surfaced); add a pinning case once fixed.

## Note

The multiple-values unit tests deliberately avoid `(values (print ...) ...)`
shapes because of this; once fixed, those tests could be tightened.
