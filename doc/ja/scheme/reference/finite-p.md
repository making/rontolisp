# finite?

`(finite? z)`

`z` が無限大でも NaN でもなければ `#t` を返します。正確数はすべて有限です。

```scheme
(finite? 1.5) ; => #t
(finite? (/ 1.0 0.0)) ; => #f
(finite? -inf.0) ; => #f
```
