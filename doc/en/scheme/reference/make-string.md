# make-string

`(make-string k)` `(make-string k char)`

Returns a new mutable string of length `k`, every element `char`. Without `char` the string is filled with spaces (R7RS leaves the contents unspecified).

```scheme
(make-string 3 #\x) ; => "xxx"
(make-string 2) ; => "  "
```
