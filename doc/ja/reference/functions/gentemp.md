# gentemp

`(gentemp &optional prefix package)`

`prefix`(既定は `"T"`)にカウンタを付けた名前のシンボルを新たに intern して返します。既存のシンボルが使っている名前は飛ばします。[`gensym`](gensym.md) と違い結果は intern される点が本質で、CLHS では非推奨ですが iterate は節のディスパッチ関数名にこれを使います。

```lisp
(let ((a (gentemp "Q")) (b (gentemp "Q"))) (eq a b)) ; => NIL
```
