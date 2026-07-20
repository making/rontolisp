# symbolp

`(symbolp object)`

Returns `t` if `object` is a symbol, otherwise `nil`. Note that `nil` and keywords are symbols, so `(symbolp nil)` and `(symbolp :foo)` are both `t`. Quoted symbols and string literals share a runtime representation but are distinguished by a leading quote character, so strings are not symbols. Works in all three backends.

```lisp
(symbolp 'foo) ; => T
```

```lisp
(symbolp "foo") ; => NIL
```
