# remove-if-not

`(remove-if-not predicate sequence)`

`sequence` の要素のうち `predicate` を満たすものだけを残した新しいシーケンスを返します (満たさない要素が取り除かれます)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。`remove-if` の補集合版です。元のシーケンスは変更されません。

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```
