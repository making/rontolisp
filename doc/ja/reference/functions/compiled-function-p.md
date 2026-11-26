# compiled-function-p

`(compiled-function-p object)`

どの関数に対しても真を返します。`(typep object 'compiled-function)` の答えも同じです。コンパイル済み/インタープリタ実行の区別は記録されず、SBCL も同じ答えを返します。

```lisp
(list (compiled-function-p #'car) (compiled-function-p 'car)) ; => (T NIL)
```
