# port?

`(port? obj)`

Returns `#t` if `obj` is a port.

```scheme
(port? (open-input-string "x")) ; => #t
```
