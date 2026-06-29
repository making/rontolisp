# length

`(length sequence)`

シーケンスの要素数を返します。リスト・文字列・ランク 1 のベクタに対して動作し、`(length nil)` は `0` です。ランク 2 の配列はシーケンスではないため、それに対して `length` を呼ぶとエラーが発生します。

```lisp
(length '(a b c d)) ; => 4
```

```lisp
(length "hello") ; => 5
```
