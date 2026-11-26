# output-port-open?

`(output-port-open? port)`

Returns `#t` if the output port `port` is still open. A port that is not an output port is an error.

```scheme
(output-port-open? (open-output-string)) ; => #t
```
