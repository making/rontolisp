# lambda

`(lambda (params...) body...)`

指定したパラメータリストと本体を持つ無名関数を作成し、スコープ内のレキシカル変数を閉じ込めます。`body` は `lambda` の作成時には評価されず、生成された関数が呼び出されるたびに実行され、本体の最後のフォームの値を返します。この関数値は `funcall`/`apply` で呼び出したり、`mapcar` のような高階関数に渡したりできます。呼び出し位置では `lambda` フォームを直接使うこともできます。例: `((lambda (x) x) 1)`。

```lisp
(funcall (lambda (x) (* x x)) 5) ; => 25
```

パラメータリストは [`defun`](defun.md) と同じラムダリストキーワード(`&optional`、`&rest`、`&key`、`&allow-other-keys`、`&aux`)をサポートします。

```lisp
(funcall (lambda (&rest xs) xs) 1 2 3) ; => (1 2 3)
```

```lisp
(mapcar (lambda (x &optional (y 100)) (+ x y)) (list 1 2 3)) ; => (101 102 103)
```
