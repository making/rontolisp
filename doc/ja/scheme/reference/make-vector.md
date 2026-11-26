# make-vector

`(make-vector k)` `(make-vector k fill)`

長さ `k` で全要素が `fill` の新しいベクタを返します。`fill` を省略すると全要素が `0` になります（R7RS では内容は未規定です）。

```scheme
(make-vector 3 'x) ; => #(x x x)
(make-vector 2) ; => #(0 0)
```
