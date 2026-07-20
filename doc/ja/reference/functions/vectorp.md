# vectorp

`(vectorp value)`

`value` がベクタのとき `t` を返します。Common Lisp では文字列はベクタなので、文字列も真になります。`typecase` の `vector` 型指定子と同様に rank は検査されません — 多次元配列も真になります。

```lisp
(vectorp (vector 1 2 3)) ; => T
```

```lisp
(list (vectorp "abc") (vectorp '(1 2))) ; => (T NIL)
```
