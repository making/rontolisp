# vectorp

`(vectorp value)`

`value` がベクタのとき `t` を返します。Common Lisp では文字列はベクタなので、文字列も真になります。ベクタは **rank 1** の配列だけなので、それ以外の rank の配列は偽になります — [`arrayp`](arrayp.md) はそれでも `t` を返します。`typep`/`typecase` の `vector` 型指定子も同じ rank を検査します。

```lisp
(vectorp (vector 1 2 3)) ; => T
```

```lisp
(list (vectorp "abc") (vectorp '(1 2))) ; => (T NIL)
```

```lisp
(list (vectorp #2A((1 2) (3 4))) (arrayp #2A((1 2) (3 4)))) ; => (NIL T)
```
