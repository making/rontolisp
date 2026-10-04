# c70. Clojure `var-get` is an unknown name

Difficulty: Low

`(var-get #'*out*)` and `(var-get #'x)` fail at lowering with `unknown name: var-get`
(measured 2026-10-04); the oracle (`clj` 1.12.6) answers the var's value, the same as
`@#'x` / `(deref #'x)`, which already work. Expected: `var-get` lowers like `deref` of a
var (one argument, and a function value), with a clojure-spec case on all four backends.
