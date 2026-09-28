# write-to-string

`(write-to-string object &key escape readably pretty circle right-margin miser-width lines pprint-dispatch length level base radix case gensym array)`

Returns `object`'s printed representation as a string. Without keywords it is the readable (`prin1`) form, an alias for [prin1-to-string](prin1-to-string.md); each keyword binds the matching printer control variable around the one print, exactly as for [write](write.md) (`:escape nil` gives the `princ` form). `#'write-to-string` as a value takes the keywords too (`(apply #'write-to-string x keys)`).

```lisp
(write-to-string '(a b 3)) ; => "(A B 3)"
```

```lisp
(write-to-string '(a b c d) :length 2 :case :downcase) ; => "(a b ...)"
```
