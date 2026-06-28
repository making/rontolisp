# stringp

`(stringp object)`

Returns `t` if `object` is a string, otherwise `nil`. Symbols are not strings, so `(stringp 'hello)` is `nil`. Works in all three backends.

```lisp
(stringp "hello") ; => t
```

```lisp
(stringp 'hello) ; => nil
```
