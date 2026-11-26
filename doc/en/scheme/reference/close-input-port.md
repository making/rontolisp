# close-input-port

`(close-input-port port)`

Like `close-port`, for an input port; any other port is an error.

```scheme
(let ((p (open-input-string "x"))) (close-input-port p) (input-port-open? p)) ; => #f
```
