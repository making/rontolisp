# make-parameter

`(make-parameter value)`, `(make-parameter value converter)`

パラメータオブジェクトを返します。引数なしの手続きで、呼ぶと現在の値を返します。その値は `value`、変換手続きを渡した場合は `(converter value)` で、[`parameterize`](parameterize.md) が別の値を束縛するまで変わりません。パラメータオブジェクトに対する `procedure?` は `#t` です。`parallel-execute` が開始したスレッドは、呼び出し側の値から始まります。

仕様との差異: パラメータオブジェクトを引数付きで呼ぶとエラーです。Gauche は値を設定します。

```scheme
((make-parameter 5 (lambda (x) (* x 2)))) ; => 10
(define radix (make-parameter 10))
(radix) ; => 10
(parameterize ((radix 2)) (radix)) ; => 2
```
