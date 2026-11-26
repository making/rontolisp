# vector-ref

`(vector-ref vector k)`

`vector` の 0 始まりの添字 `k` にある要素を返します。範囲外の添字はエラーでプログラムを終了します。

```scheme
(vector-ref #(a b c) 1) ; => b
(vector-ref #(a b c) 0) ; => a
```
