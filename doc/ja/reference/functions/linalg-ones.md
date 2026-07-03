# linalg:ones

`(linalg:ones shape)`

要素がすべて 1 の配列を作成します。`shape` は [`linalg:zeros`](linalg-zeros.md) と同様に、ランク 1 のベクタの場合は整数、ランク 2 の行列の場合はリスト `(rows cols)` です。任意の値で埋めるには [`linalg:full`](linalg-full.md) を使ってください。

```lisp
(linalg:ones '(2 2)) ; => #2A((1 1) (1 1))
```
