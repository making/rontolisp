# close-output-port

`(close-output-port port)`

Like `close-port`, for an output port; any other port is an error. `get-output-string` still answers what a closed string port holds.

```scheme
(let ((p (open-output-string))) (close-output-port p) (output-port-open? p)) ; => #f
```
