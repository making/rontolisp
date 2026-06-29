# maplist

`(maplist function list)`

`mapcar` と同様ですが、`function` は `list` の要素ではなく連続する cdr（末尾）に適用されます。まずリスト全体、次にその残り、というように最後の単一要素の末尾まで進みます。その結果からなる新しいリストを返します。単一リストの形式のみ対応しています。

```lisp
(maplist #'identity '(1 2 3)) ; => ((1 2 3) (2 3) (3))
```
