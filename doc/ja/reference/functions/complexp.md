# complexp

`(complexp object)`

`object` が複素数なら `t`、そうでなければ `nil` を返す。実数は複素数ではない。虚部がゼロでも [`complex`](complex.md) が実数に降格させるためである。4バックエンドすべてで動作する。

```lisp
(complexp #c(1 2)) ; => T
```

```lisp
(complexp 1) ; => NIL
```
