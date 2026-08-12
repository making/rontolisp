# get-internal-run-time

`(get-internal-run-time)`

消費した実行 (CPU) 時間をミリ秒で返します。2 回の測定値の差を取ることで処理時間の測定に使えます。すべてのバックエンドが整数を返します。`get-internal-real-time` と同様、絶対値ではなく 2 回の測定値の差のみが意味を持ちます。

```lisp
(get-internal-run-time)
```

計算を 2 回の呼び出しで挟み、差を取ることで消費した実行時間を得ます。値は過去の実行に依存するため非決定的です。`--no-wasi` モジュールにはホストが設定した 1 つの時計しかないため、そこでは `get-internal-real-time` と同じ値を返し、この差は 0 になります ([時計と乱数のガイド](../../guides/clock-and-random.md#setting-the-clock----ronto-set-time))。
