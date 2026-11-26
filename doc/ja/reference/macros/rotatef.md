# rotatef

`(rotatef place...)`

[`setf`](setf.md) 可能な各場所の値を左へ回転します。最初の場所は 2 番目の古い値を受け取り、以下同様に、最後の場所は最初の古い値を受け取ります。nil を返します。どの場所も書き込む前に一時変数へ読み出すため、場所が 2 つの `rotatef` は値を入れ替えます。

```lisp
(let ((x 1) (y 2))
  (rotatef x y)
  (list x y)) ; => (2 1)
```

```lisp
(let ((a 1) (b 2) (c 3))
  (rotatef a b c)
  (list a b c)) ; => (2 3 1)
```

```lisp
(let ((x (cons 1 2)))
  (rotatef (car x) (cdr x))
  x) ; => (2 . 1)
```
