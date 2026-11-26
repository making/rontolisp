# current-output-port

`(current-output-port)`

Returns the current output port: standard output, unless `parameterize` has bound another textual output port. `current-output-port` is a parameter object, so `(parameterize ((current-output-port port)) body ...)` sends everything `body` writes without a port argument to `port`; the binding is undone however `body` is left.

```scheme
(let ((p (open-output-string))) (parameterize ((current-output-port p)) (display "hi")) (get-output-string p)) ; => "hi"
```
