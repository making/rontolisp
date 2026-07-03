# linalg:outer

`(linalg:outer u v)`

2 つのベクタの外積を返します。結果の行列の要素 `(i j)` は、`u` の要素 `i` と `v` の要素 `j` の積です。numpy と同様に、両方の入力はまず平坦化されるため、行列も受け付けられ、行優先の要素列として扱われます。内積には [`linalg:dot`](linalg-dot.md) を使ってください。

```lisp
(linalg:outer (linalg:from-list '(1 2)) (linalg:from-list '(3 4 5))) ; => #2A((3 4 5) (6 8 10))
```
