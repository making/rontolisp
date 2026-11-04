# i38. `*read-base*` has no implementation

Difficulty: Medium

Measured 2026-11-05. `*READ-BASE*` is only exported (`PackageRegistry.CL_EXPORTED_ONLY`): reading it signals
`The variable *READ-BASE* is unbound`, and no backend's reader honors a binding of it.

```lisp
(print *read-base*)                                      ; SBCL: 10
(print (let ((*read-base* 16)) (read-from-string "ff"))) ; SBCL: 255
```

Consumer: eclector loads on the interpreter, and `(eclector.reader:read-from-string "(1 #1=(a b) #1#)")` stops in
`interpret-token` at `(state-value client '*read-base*)`. The name moves out of `CL_EXPORTED_ONLY` once it has a
value on all four backends; the integer token rule needs the base in `LispReader` and the compiled `read`.
