# remove-if-not

`(remove-if-not predicate sequence &key key)`

`sequence` の要素のうち `predicate` を満たすものだけを残した新しいシーケンスを返します (満たさない要素が取り除かれます)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。`remove-if` の補集合版です。元のシーケンスは変更されません。`:key` を渡すと述語はキー適用後の値を見ますが、残る要素は元の要素です。

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```

```lisp
(remove-if-not #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((2 B))
```
