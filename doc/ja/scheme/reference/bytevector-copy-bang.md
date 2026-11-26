# bytevector-copy!

`(bytevector-copy! to at from)` `(bytevector-copy! to at from start)` `(bytevector-copy! to at from start end)`

`from` の `start`（含む、既定は 0）から `end`（含まない、既定は末尾）までの要素を、`to` の添字 `at` から先へコピーし、未規定値を返します。`to` と `from` が同じバイトベクタで範囲が重なるときは、元の範囲をいったん一時領域へコピーしたのと同じ結果になります。

```scheme
(let ((b (bytevector 1 2 3 4 5))) (bytevector-copy! b 1 #u8(9 8 7) 0 2) b) ; => #u8(1 9 8 4 5)
(let ((b (bytevector 1 2 3 4 5))) (bytevector-copy! b 1 b 0 3) b) ; => #u8(1 1 2 3 5)
```
