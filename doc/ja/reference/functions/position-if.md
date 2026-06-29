# position-if

`(position-if predicate list)`

`list` の要素のうち `predicate` を満たす最初の要素の 0 始まりのインデックスを返します。満たす要素がなければ `nil` を返します。要素そのものではなく整数の位置を返します（`find-if` と比較してください）。

```lisp
(position-if #'evenp '(1 3 6 7)) ; => 2
```
