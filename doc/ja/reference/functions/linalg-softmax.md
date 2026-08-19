# linalg:softmax

`(linalg:softmax array &key axis)`

`array` の softmax、すなわち合計が 1 になるよう正規化した `exp(x - max)` を返します。`:axis` なしでは配列全体が 1 つの分布になり (scipy の `softmax` のデフォルト)、整数の `:axis` (負の値は末尾から数えます) を渡すとその軸に沿った各スライスが個別に正規化されます。後者が torch の `softmax(x, dim)` に相当するアテンション重みの形です。最大値を先に引くため大きなロジットでもオーバーフローせず、`-infinity` の要素 (マスクされた位置、[`linalg:where`](linalg-where.md) を参照) はちょうど `0.0` になります。

[`linalg:relu`](linalg-relu.md) と同様に `softmax` は numpy 本体にはありませんが、活性化層が必要とする配列レベルのプリミティブなのでここに置いています。対数版は [`linalg:log-softmax`](linalg-log-softmax.md) です。

```lisp
(linalg:softmax #(1 1 1 1))               ; => #d(0.25 0.25 0.25 0.25)
(linalg:softmax #2A((0 0) (1 1)) :axis 1) ; => #d((0.5 0.5) (0.5 0.5))
```
