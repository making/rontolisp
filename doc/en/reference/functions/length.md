# length

`(length sequence)`

Returns the number of elements in a sequence. It works on lists, strings, and rank-1 vectors; `(length nil)` is `0`. A rank-2 array is not a sequence, so calling `length` on one signals an error; any other non-sequence (a number, a symbol, a hash table) signals a `type-error`.

```lisp
(length '(a b c d)) ; => 4
```

```lisp
(length "hello") ; => 5
```

```lisp
(handler-case (length 5) (type-error (e) (princ-to-string e))) ; => "LENGTH: The value 5 is not of type SEQUENCE"
```
