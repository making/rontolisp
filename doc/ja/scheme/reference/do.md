# do

`(do ((variable init step)...) (test expression...) command...)`

反復構文です。各 `variable` を `init` に束縛し、`test` を繰り返し評価します。偽の間は `command` を実行して各変数を `step` に束縛し直します（`step` のない変数は値を保ちます）。`test` が真になると `expression` を評価して最後の値を返し、式がなければ未規定値を返します。`do` は一定のスタックで動きます。

```scheme
(do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((= i 5) sum)) ; => 10
(do ((vec (make-vector 3)) (i 0 (+ i 1))) ((= i 3) vec) (vector-set! vec i (* i i))) ; => #(0 1 4)
```
