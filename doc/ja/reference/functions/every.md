# every

`(every predicate sequence)`

`sequence` の各要素に `predicate` を適用し、すべての呼び出しが非 nil なら `t` を返し、1 つでも失敗した時点で `nil` を返します (最初の失敗でテストを打ち切ります)。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。空のシーケンスでは `t` を返します。単一シーケンス形式のみで、述語は 1 度に 1 要素を受け取ります。

```lisp
(every #'evenp '(2 4 6)) ; => T
```

```lisp
(every #'digit-char-p "123") ; => T
```
