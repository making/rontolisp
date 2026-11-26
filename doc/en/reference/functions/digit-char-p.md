# digit-char-p

`(digit-char-p character &optional radix)`

Returns the integer weight of `character` as a digit in the given `radix` (default 10), or `nil` if it is not a valid digit in that radix. For example `#\7` is `7`, and `#\f` is `15` in radix 16. Letters are accepted as digits for radices above 10, case-insensitively. A non-character signals a `type-error` naming the operator, and so does a `radix` that is not an integer from 2 to 36 (the type is `(INTEGER 2 36)`).

```lisp
(digit-char-p #\7) ; => 7
```
