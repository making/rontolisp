# characterp

`(characterp object)`

Returns `t` if `object` is a character, otherwise `nil`. Character literals are written with the `#\` prefix, e.g. `#\a`. A one-character string is not a character, so `(characterp "a")` is `nil`. Works in all three backends.

```lisp
(characterp #\a) ; => t
```

```lisp
(characterp "a") ; => nil
```
