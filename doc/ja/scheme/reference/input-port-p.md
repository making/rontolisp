# input-port?

`(input-port? obj)`

`obj` が入力ポートなら（開いていても閉じていても）`#t` を返します。

```scheme
(input-port? (open-output-string)) ; => #f
```
