# write

`(write object &key stream escape readably pretty circle right-margin miser-width lines pprint-dispatch length level base radix case gensym array)`

Writes `object` to `stream` (default standard output) and returns `object`. Each keyword binds the matching printer control variable around that one print, exactly as Common Lisp defines them. `:escape` (default: the value of `*print-escape*`, which is `t`) selects the readable `prin1` form; `:escape nil` selects the `princ` form. `:length` and `:level` truncate lists and vectors (`(1 2 ...)`, `#`), `:case` converts symbol case, `:gensym nil` drops a gensym's `#:`, and `:base` / `:radix` re-spell integers and ratios (`FF`, `#xFF`, `255.`). `:pretty`, `:circle`, `:array` and the three width keywords are accepted and bind their variable, but the printer's layout never changes -- see `pprint` for why. `write-to-string` takes the same keywords.

```lisp
(with-output-to-string (s) (write "hi" :stream s :escape nil)) ; => "hi"
```

```lisp
(with-output-to-string (s) (write '(foo bar baz) :stream s :case :downcase :length 2)) ; => "(foo bar ...)"
```
