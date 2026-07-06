# make-string

`(make-string size &key initial-element element-type)`

Returns a fresh string of `size` characters, each equal to `:initial-element` (default a space; the standard leaves the fill character implementation-defined). `:element-type` is accepted and ignored -- rontolisp has a single string representation. Available on all backends except `--no-gc`.

```lisp
(make-string 3 :initial-element #\x) ; => "xxx"
```
