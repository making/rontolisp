# sleep

`(sleep seconds)`

`seconds` の間ブロックして `nil` を返します。`seconds` には任意の非負の実数を渡せるので、1秒未満の待機は `0.5` のように書けます。0以下の値はすぐに返ります。待機時間はミリ秒単位に丸められます。これは [`get-internal-real-time`](get-internal-real-time.md) と共通の、全バックエンドが共有する分解能です。

インタプリタとJVMはスレッドを停止させます。`--component` は本物のホストタイマー (`wasi:clocks`) をモジュールスケジューラ経由で待つので、待機中もCPUを消費せず、保留中の他のタスクは進行します。**ビジーウェイトするのはWASM Preview 1 だけです**。期限までクロックをループします。Preview 1 のインポートにはクロックはあっても待機できるタイマーが無く、時間を経過させるには消費するしかないためです。待機自体は正直ですが、コアを1つ使い、その間インスタンスをブロックします。`--no-wasi` モジュールは待機せず**シグナル**します。タイマーをインポートせず、時計もホストが書き込んだときだけ動くため、呼び出しの実行中に時間を経過させられないからです([時計と乱数のガイド](../../guides/clock-and-random.md))。

```lisp
(sleep 0) ; => NIL
```

```console
(let ((start (get-internal-real-time)))
  (sleep 0.5)
  (print (>= (- (get-internal-real-time) start) 500)))
```
