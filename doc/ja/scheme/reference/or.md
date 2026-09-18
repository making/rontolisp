# or

`(or test...)`

test を左から右へ評価し、`#f` でない最初の値を返します（残りは評価しません）。すべて偽なら `#f` を返します。`(or)` は `#f` です。

```scheme
(or #f 2 3) ; => 2
(or (memq 'c '(a b c d)) 'none) ; => (c d)
(or #f #f) ; => #f
```
