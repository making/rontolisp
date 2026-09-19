# current-error-port

`(current-error-port)`

Returns the current error port: standard error, unless `parameterize` has bound another textual output port. `current-error-port` is a parameter object like `current-output-port`.

```scheme
(output-port? (current-error-port)) ; => #t
```
