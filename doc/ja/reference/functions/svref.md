# svref

`(svref vector index)`

指定された 0 始まりの `index` における、ランク 1 の配列の要素を返します。添字をちょうど 1 つに限定した [`aref`](aref.md) のように振る舞い、同様に `setf` の場所としても使えます: `(setf (svref v i) value)` は要素を置き換えます。`#'svref` は第一級の関数値なので、他の関数と同じように `mapcar`/`funcall` に渡せます。

```lisp
(svref (vector 10 20 30) 1) ; => 20
(let ((v (vector 1 2 3)))
  (setf (svref v 0) 99)
  (svref v 0)) ; => 99
```
