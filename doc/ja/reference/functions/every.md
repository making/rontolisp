# every

`(every predicate list)`

`list` の各要素に `predicate` を適用し、すべての呼び出しが非 nil なら `t` を返し、1 つでも失敗した時点で `nil` を返します (最初の失敗でテストを打ち切ります)。空のリストでは `t` を返します。単一リスト形式のみで、述語は 1 度に 1 要素を受け取ります。

```lisp
(every #'evenp '(2 4 6)) ; => t
```
