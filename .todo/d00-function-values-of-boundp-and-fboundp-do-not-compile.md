# d00. `#'boundp` and `#'fboundp` do not compile

Difficulty: Low

```lisp
(defvar *fv* 1)
(print (mapcar #'boundp '(*fv* *nope*))) ; interpreter / SBCL: (T NIL)
(print (funcall #'fboundp 'car))          ; interpreter / SBCL: T
;; JVM, wasm Preview 1, component: "Cannot compile: BOUNDP" / "Cannot compile: FBOUNDP"
```

`#'symbol-value` has a reference-gated `BuiltinFunctionWrappers` entry; `boundp` and `fboundp`
have none (`.kb/symbol-runtime-api.md`), so a function value of either fails the compile. A wrapper
body is a computed `(boundp x)` / `(fboundp x)`; `boundp`'s already answers a binding of a special
declared without a value through `%boundp-dynamic` once the injected wrapper counts as a computed
probe (`LispMacroExpander.boundpProbes` reads the injected forms;
`.kb/dynamic-special-variables.md`, "Bound-ness of a special without a value"). Gate both on the
reference like `#'symbol-value`, so a program that never names them stays byte-identical, and pin
the four backends in ci-spec.
