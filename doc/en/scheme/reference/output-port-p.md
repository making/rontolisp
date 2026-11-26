# output-port?

`(output-port? obj)`

Returns `#t` if `obj` is an output port, open or closed.

```scheme
(output-port? (open-output-string)) ; => #t
```
