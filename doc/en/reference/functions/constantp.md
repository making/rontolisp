# constantp

`(constantp form)`

Returns `t` if `form` is a constant object and `nil` otherwise. This is a lite implementation: it recognizes self-evaluating objects (numbers, strings, characters, keywords, `t`, and `nil`) and `(quote x)` forms. Anything else -- including a plain symbol or a function-call form -- yields `nil`. False negatives are harmless (a consumer just defers the work to runtime). Available on all backends except `--no-gc`.

```lisp
(list (constantp 5) (constantp 'x) (constantp '(quote y))) ; => (t nil t)
```
