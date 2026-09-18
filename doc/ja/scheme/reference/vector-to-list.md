# vector->list

`(vector->list vector)` `(vector->list vector start)` `(vector->list vector start end)`

`vector` の要素、または `start`（含む）から `end`（含まない、既定はベクタの末尾）までの部分の要素のリストを返します。

```scheme
(vector->list #(1 2 3)) ; => (1 2 3)
(vector->list #(1 2 3 4) 1 3) ; => (2 3)
```
