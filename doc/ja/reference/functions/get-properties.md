# get-properties

`(get-properties plist indicator-list)`

`plist` を走査し、`indicator-list` のいずれかと `eq` な最初のインジケータを探して 3 つの値(見つかったインジケータ、その値、そのインジケータから始まる `plist` の末尾)を返します。見つからない場合は 3 つとも nil です。これにより格納された nil と存在しないプロパティを区別できます([`getf`](getf.md) との違い)。

```lisp
(multiple-value-list (get-properties '(a 1 b 2) '(b))) ; => (B 2 (B 2))
```

```lisp
(multiple-value-list (get-properties '(a 1) '(z))) ; => (NIL NIL NIL)
```
