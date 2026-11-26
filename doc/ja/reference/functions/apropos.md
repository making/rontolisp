# apropos

`(apropos string &optional package)`

[`apropos-list`](apropos-list.md)
の各一致を1行ずつ印字し、`nil` を返す。

```lisp
(apropos "CAR" :cl)
```
```text
CAR
MAPCAR
```
