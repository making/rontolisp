# exact-integer-sqrt

`(exact-integer-sqrt k)`

2 つの値を返します。`s*s` が `k` を超えない最大の正確な整数 `s` と、残り `k - s*s` です。`k` は正確な非負整数でなければならず、それ以外はエラーになります。

```scheme
(exact-integer-sqrt 17) ; => 4, 1
(exact-integer-sqrt 16) ; => 4, 0
```
