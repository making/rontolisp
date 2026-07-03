# position

`(position item sequence)`

`sequence` の要素のうち `item` に `eql` な最初の要素の 0 始まりのインデックスを返します。一致する要素がなければ `nil` を返します。シーケンスにはリストまたは文字列を渡せます。文字列の要素は文字です。要素を返す `find` とは異なり、`position` は整数の位置を返します。

```lisp
(position 3 '(1 2 3)) ; => 2
```

```lisp
(position #\space "hello world") ; => 5
```
