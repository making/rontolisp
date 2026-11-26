# linalg:trace

`(linalg:trace matrix)`

正方行列のトレース、すなわち主対角線上の要素の合計を返します。正方でない（またはランク 1 の）引数はエラーを通知します。正方行列のもうひとつの古典的なスカラーについては [`linalg:det`](linalg-det.md) も参照してください。

```lisp
(linalg:trace #2A((1 2) (3 4))) ; => 5
```
