# lognand

`(lognand integer1 integer2)`

`integer1` と `integer2` のビット単位NAND、すなわち `(lognot (logand integer1 integer2))`。引数はちょうど2個。全バックエンドで任意精度の整数に対して正確です。

```lisp
(lognand 12 10) ; => -9
```
