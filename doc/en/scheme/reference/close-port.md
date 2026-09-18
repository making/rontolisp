# close-port

`(close-port port)`

Closes `port`; reading or writing it afterwards is an error (`write: the port is closed: #<textual-output-port>`). Closing a closed port does nothing. Closing a standard port only marks the port object closed.

```scheme
(let ((p (open-output-string))) (close-port p) (output-port-open? p)) ; => #f
```
