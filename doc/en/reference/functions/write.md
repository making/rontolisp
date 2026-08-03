# write

`(write object &key stream escape readably pretty circle right-margin miser-width lines pprint-dispatch)`

Writes `object` to `stream` (default standard output) and returns `object`. Each keyword binds the matching printer control variable around that one print, exactly as Common Lisp defines them. `:escape` (default: the value of `*print-escape*`, which is `t`) selects the readable `prin1` form; `:escape nil` selects the `princ` form. The rest are accepted and bind their variable, but the printer's layout never changes -- see `pprint` for why. Note that `write-to-string` takes no keywords here: use `(with-output-to-string (s) (write x :stream s ...))` when you need them.

```lisp
(with-output-to-string (s) (write "hi" :stream s :escape nil)) ; => "hi"
```
