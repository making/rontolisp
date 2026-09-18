# (scheme write)

Writing a datum to the current output port, or to the port given as the last argument.

| Name | Example | Result |
|---|---|---|
| `display` | `(display '(1 "two" #\3))` | prints `(1 two 3)` |
| `write` | `(write '(1 "two" #\3))` | prints `(1 "two" #\3)` |
| `write-shared` | `(write-shared (list x x))` | prints `(#0=(1 2) #0#)` for `x` = `(1 2)` |
| `write-simple` | `(write-simple (list x x))` | prints `((1 2) (1 2))` for `x` = `(1 2)` |
