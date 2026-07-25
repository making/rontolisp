# arrayp

`(arrayp object)`

オブジェクトが配列なら `t`、そうでなければ `nil` を返します。Common Lisp では文字列も配列 (文字のランク 1 配列) なので `(arrayp "abc")` は真です。ランク 1 の配列と文字列については [`vectorp`](vectorp.md) も同じ答えを返します。

```lisp
(list (arrayp (vector 1 2)) (arrayp "abc") (arrayp '(1 2))) ; => (T T NIL)
```
