# position

`(position item list)`

`list` の要素のうち `item` に `eql` な最初の要素の 0 始まりのインデックスを返します。一致する要素がなければ `nil` を返します。要素を返す `find` とは異なり、`position` は整数の位置を返します。

```lisp
(position 3 '(1 2 3)) ; => 2
```
