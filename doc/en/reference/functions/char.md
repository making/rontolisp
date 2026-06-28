# char schar

`(char string index)` -- `(schar string index)`

Returns the character at the 0-based `index` of `string`. `char` and `schar` behave identically here; in Common Lisp `schar` is the simple-string variant, but rontolisp treats them the same. The WASM backend indexes strings by byte, so indexing is well-defined for ASCII text only.

```lisp
(char "hello" 1) ; => #\e
```
