# 64: funcall/apply of a builtin wrapper with mismatched arity fails silently

**Status:** pre-existing limitation, characterized 2026-07-05 while testing
the multiple-values unit (`.kb/multiple-values.md`). Reproduced on a clean
pre-change build (HEAD = 02bcfde).

## Symptom

`BuiltinFunctionWrappers` gives every builtin a FIXED arity (`#'+`/`#'list`
are binary/unary wrappers), but in CL these functions are variadic. Calling a
wrapper through `funcall`/`apply` with a different argument count does not
signal a sensible error on the compile paths:

```lisp
(funcall #'+ 1 2)        ; 3 everywhere (matches the binary wrapper)
(funcall #'+ 1 2 3)      ; interpreter: 6   JVM: nil (silent!)   WASM: trap (unreachable)
(funcall #'list 1 2 3)   ; interpreter: (1 2 3)   JVM: nil   WASM: trap
(apply #'+ (list 1 2 3)) ; same pattern
```

The JVM silent-nil is the worst mode: the `_invoke_N` dispatcher apparently
finds no arity-N entry for the function index and yields nil instead of
erroring. Also observed: which programs "work" can vary with what else is in
the program (a 5-arg `funcall #'list` returned the full list in one program
and nil in another — presumably a dispatcher/index coincidence worth
understanding while fixing).

## Possible directions

- Minimum: make the JVM dispatcher signal "wrong number of arguments" instead
  of returning nil (WASM already traps, message could improve).
- Better: variadic wrappers for the naturally-variadic builtins (`+`, `-`,
  `*`, `/`, `list`, `min`/`max`, comparisons chains...) now that a `&rest`
  wrapper exists (`values` was the first, added by the multiple-values unit).
- Document the fixed wrapper arities on the functions reference page either
  way (today only `multiple-value-call`'s page mentions it).

## Affected callers

`multiple-value-call` lowers to `funcall`, so it inherits this; its doc page
steers users to user-defined functions/lambdas for non-wrapper arities.
