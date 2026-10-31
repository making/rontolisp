# constantp

`(constantp form &optional environment)`

Returns `t` if `form` is a constant object and `nil` otherwise. This is a lite implementation: it recognizes self-evaluating objects (numbers, strings, characters, keywords, `t`, and `nil`), `(quote x)` forms, and symbols naming a constant variable (a standard constant such as `pi`, or a name a `defconstant` defines). Anything else -- including any other symbol or a function-call form -- yields `nil`. False negatives are harmless (a consumer just defers the work to runtime). The optional `environment` argument is accepted and ignored (a macro's `&environment` parameter is bound to nil). Available on all backends except `--no-gc`.

```lisp
(list (constantp 5) (constantp 'x) (constantp '(quote y))) ; => (T NIL T)
(defconstant +limit+ 10)
(constantp '+limit+) ; => T
```
