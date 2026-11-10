# rontolisp:unix-time

`(rontolisp:unix-time)`

実時間の時計を 2 値で返します。Unix エポック (1970-01-01 GMT) からの秒数と、その秒の中のナノ秒 (0〜999999999) です。[`get-universal-time`](get-universal-time.md) は秒で止まりますが、これは同じ時計の秒未満までを返します。そのため 1 秒の中の読み取りどうしを順序付けでき、2 回の読み取りから経過時間を求められます。第 1 値は `(- (get-universal-time) 2208988800)` です。

秒未満の値の取得元は、インタプリタと JVM では `java.time.Instant`、WASM Preview 1 (と `--native`) では WASI の `clock_time_get`、`--component` では `wasi:clocks/system-clock`、`--no-wasi` ではホストが `__ronto_set_time` で書き込んだナノ秒です。`--no-wasi` ではホストが書き込むまでこの呼び出しはシグナルします([時計と乱数のガイド](../../guides/clock-and-random.md))。どこまで細かいかはホストの時計によります。JVM と wasmtime の時計は少なくともマイクロ秒です。

`local-time:now` はこの時計を読むため、そのタイムスタンプにも同じ秒未満の値が入ります。

```lisp
(multiple-value-bind (seconds nanoseconds) (rontolisp:unix-time)
  (list (integerp seconds) (<= 0 nanoseconds 999999999))) ; => (T T)
```

値は呼び出しごとに変わるため、ここでは型のみを示します。
