# current-input-port

`(current-input-port)`

Returns the current input port: standard input, unless `parameterize` has bound another textual input port. `current-input-port` is a parameter object, so `(parameterize ((current-input-port port)) body ...)` makes `read`, `read-char`, `peek-char`, `read-line` and `read-string` read `port` for the dynamic extent of `body`.

```scheme
(parameterize ((current-input-port (open-input-string "(1 2) x"))) (read)) ; => (1 2)
```
