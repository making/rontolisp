# get-internal-real-time

`(get-internal-real-time)`

経過した実時間 (壁時計時間) をミリ秒で返します。2 回の測定値の差を取ることで処理の計時に使えます。インタプリタと JVM は整数を、WASM バックエンドは浮動小数点数を返します。絶対値はカレンダー時刻としてではなく、別の測定値との相対値としてのみ意味を持ちます。

```lisp
(get-internal-real-time)
```

典型的には `(- (get-internal-real-time) start)` のように使い、計算に要したミリ秒数を測ります。現在の時計を反映するため、値は非決定的です。
