# some

`(some predicate sequence)`

`sequence` の各要素に `predicate` を適用し、最初の非 nil の結果を返します (見つかった時点で打ち切ります)。すべての要素が失敗した場合は `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。戻り値は述語の結果であり、必ずしも `t` ではない点に注意してください。単一シーケンス形式のみです。

```lisp
(some #'oddp '(2 4 5)) ; => T
```

```lisp
(some #'digit-char-p "abc1") ; => 1
```
