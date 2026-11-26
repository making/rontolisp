# with-exception-handler

`(with-exception-handler handler thunk)`

`handler` を設定した状態で `thunk` を引数なしで呼び出し、その値を返します。`thunk` の中で発生したオブジェクトは `handler` に渡され、`handler` はこのハンドラより外側のハンドラが有効な状態で実行されます。`raise-continuable` ではハンドラの値がその呼び出しに返されます。それ以外ではハンドラは継続（`call/cc`）で抜けるか発生させる必要があり、戻ると二次エラーになります。

仕様との差異: 組み込み手続きのエラー（`(+ 1 'a)`）では、どのバックエンドでもハンドラは `thunk` を抜けた後に実行されます（その中の `dynamic-wind` の `after` は実行済みです）。R7RS ではエラーの起きた場所で実行されます。`raise`、`raise-continuable`、`error` はその場でハンドラを呼びます。

```scheme
(call/cc (lambda (k) (with-exception-handler (lambda (e) (k (list 'handled e))) (lambda () (raise 'boom))))) ; => (handled boom)
(with-exception-handler (lambda (e) 0) (lambda () (+ 5 (raise-continuable 'use-zero)))) ; => 5
```
