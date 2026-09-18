# string-ref

`(string-ref string k)`

Returns the character at zero-based index `k` of `string`. An index out of range ends the program with an error, whose message spells the Common Lisp name `CHAR`.

```scheme
(string-ref "hello" 1) ; => #\e
(string-ref "hello" 0) ; => #\h
```
