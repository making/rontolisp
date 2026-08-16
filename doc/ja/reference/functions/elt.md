# elt

`(elt sequence index)`

`sequence` の 0 始まりの `index` にある要素を返します。文字列の場合は文字を、リストやベクタの場合はその要素を返します（リストの走査は引数を入れ替えた `nth` と同じです）。`setf` の place としても使えます。書き込み時にシーケンスの種類ごとに何が起きるかは [`setf`](../macros/setf.md) を参照してください。

```lisp
(elt '(a b c) 1) ; => B
```

```lisp
(elt "abcd" 1) ; => #\b
```

```lisp
(elt (vector 10 20 30) 2) ; => 30
```
