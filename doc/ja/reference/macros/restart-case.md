# restart-case

`(restart-case form (restart-name (arg...) [option...] body...)...)`

主フォーム `form` のみへの簡易展開です。**リスタートシステム**は存在しないため（コンディション自体は存在します — [`handler-case`](handler-case.md) 参照）、リスタート節は到達不能（`invoke-restart` する手段がない）であり破棄されます。主フォームが評価されてその値が返ります。シグナルするフォーム（例: [`error`](error.md)）は通常どおりシグナルし、外側の `handler-case` で捕捉できます。[`check-type`](check-type.md)/[`assert`](assert.md) と同じ「リスタートなし」のセマンティクスです。

```lisp
(restart-case (+ 1 2)
  (continue () 99)) ; => 3
```
