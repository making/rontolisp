# input-port-open?

`(input-port-open? port)`

Returns `#t` if the input port `port` is still open. A port that is not an input port is an error.

```scheme
(let ((p (open-input-string "x"))) (close-port p) (input-port-open? p)) ; => #f
```
