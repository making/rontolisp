# alphanumericp

`(alphanumericp character)`

Returns true if `character` is a letter or a decimal digit and `nil` otherwise. For a digit the returned value is its weight (like `digit-char-p`); for a letter it is `t` — both are true. In the WASM backend the letter test recognizes the ASCII letters `a`-`z` and `A`-`Z` only.

```lisp
(alphanumericp #\x) ; => t
```

```lisp
(alphanumericp #\-) ; => nil
```
