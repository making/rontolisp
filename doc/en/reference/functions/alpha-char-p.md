# alpha-char-p

`(alpha-char-p character)`

Returns `t` if `character` is an alphabetic letter and `nil` otherwise (for example a digit yields `nil`). In the WASM backend the test recognizes the ASCII letters `a`-`z` and `A`-`Z` only.

```lisp
(alpha-char-p #\x) ; => t
```
