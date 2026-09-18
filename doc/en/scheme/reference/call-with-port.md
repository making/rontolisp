# call-with-port

`(call-with-port port proc)`

Calls `proc` with `port`, closes `port` when `proc` returns, and returns what `proc` returned (all of its values). If `proc` does not return, `port` stays open.

```scheme
(call-with-port (open-input-string "(1 2)") read) ; => (1 2)
```
