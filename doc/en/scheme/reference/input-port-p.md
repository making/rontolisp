# input-port?

`(input-port? obj)`

Returns `#t` if `obj` is an input port, open or closed.

```scheme
(input-port? (open-output-string)) ; => #f
```
