# output-port?

`(output-port? obj)`

`obj` が出力ポートなら（開いていても閉じていても）`#t` を返します。

```scheme
(output-port? (open-output-string)) ; => #t
```
