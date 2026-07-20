# keywordp

`(keywordp object)`

Returns `t` if `object` is a keyword -- a symbol written with a leading colon, such as `:foo` -- otherwise `nil`. An ordinary symbol is not a keyword, so `(keywordp 'foo)` is `nil`. Works in all three backends.

```lisp
(keywordp :foo) ; => T
```

```lisp
(keywordp 'foo) ; => NIL
```
