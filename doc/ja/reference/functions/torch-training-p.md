# torch:training-p

`(torch:training-p module)`

モジュールが学習モードなら `T`、評価モードなら `NIL` を返します。モジュールは学習モードで生成され、[`torch:train`](torch-train.md) と [`torch:eval`](torch-eval.md) がサブモジュールごと再帰的に切り替えます。

```lisp
(torch:training-p (torch:dropout 0.5))              ; => T
(torch:training-p (torch:eval (torch:dropout 0.5))) ; => NIL
```
