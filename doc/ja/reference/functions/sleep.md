# sleep

`(sleep seconds)`

`seconds` の間ブロックして `nil` を返します。`seconds` には任意の非負の実数を渡せるので、1秒未満の待機は `0.5` のように書けます。0以下の値はすぐに返ります。待機時間はミリ秒単位に丸められます。これは [`get-internal-real-time`](get-internal-real-time.md) と共通の、全バックエンドが共有する分解能です。

インタプリタとJVMはスレッドを停止させます。WASM Preview 1(したがって `--native` 実行ファイルも)はホストの `poll_oneoff` でクロックのサブスクリプションを待ってブロックするので、CPUを消費しません。ホストが早く戻った場合は、残りの時間をもう一度待ちます。`--component` は本物のホストタイマー (`wasi:clocks`) をモジュールスケジューラ経由で待つので、待機中もCPUを消費せず、保留中の他のタスクは進行します。`--no-wasi` モジュールは待機せず**シグナル**します。タイマーをインポートせず、時計もホストが書き込んだときだけ動くため、呼び出しの実行中に時間を経過させられないからです([時計と乱数のガイド](../../guides/clock-and-random.md))。

```lisp
(sleep 0) ; => NIL
```

```console
(let ((start (get-internal-real-time)))
  (sleep 0.5)
  (print (>= (- (get-internal-real-time) start) 500)))
```
