# apropos

`(apropos string &optional package)`

Prints each [`apropos-list`](apropos-list.md) match on its own line and returns
`nil`.

```lisp
(apropos "CAR" :cl)
```
```text
CAR
MAPCAR
```
